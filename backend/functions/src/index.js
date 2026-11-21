const { initializeApp } = require('firebase-admin/app');
const { getFirestore, FieldValue } = require('firebase-admin/firestore');
const { getMessaging } = require('firebase-admin/messaging');
const { onCall, HttpsError } = require('firebase-functions/v2/https');
const { onDocumentCreated } = require('firebase-functions/v2/firestore');
const { setGlobalOptions } = require('firebase-functions/v2');

initializeApp();
setGlobalOptions({ region: 'asia-southeast1', maxInstances: 3 });
// Construct the client lazily so CLI function discovery never waits on credentials.
const database = () => getFirestore();
const transitions = {
  Pending: ['Under Review', 'Rejected'],
  'Under Review': ['In Progress', 'Resolved', 'Rejected'],
  'In Progress': ['Under Review', 'Resolved'],
  Resolved: ['Under Review'],
  Rejected: ['Under Review'],
};

// Admin SDK bypasses Rules, so authorization and validation are mandatory here.
exports.updateReportStatus = onCall(async (request) => {
  if (!request.auth) throw new HttpsError('unauthenticated', 'Sign in first.');
  if (request.auth.token.official !== true || typeof request.auth.token.barangay !== 'string') {
    throw new HttpsError('permission-denied', 'Only an assigned barangay official can update reports.');
  }
  const { reportId, status, priority } = request.data || {};
  if (typeof reportId !== 'string' || !/^[A-Za-z0-9_-]{1,128}$/.test(reportId)
      || !Object.hasOwn(transitions, status)
      || (priority !== undefined && !['Low', 'Medium', 'High'].includes(priority))) {
    throw new HttpsError('invalid-argument', 'Provide a valid reportId, status and optional priority.');
  }
  const db = database();
  const ref = db.collection('reports').doc(reportId);
  return db.runTransaction(async (tx) => {
    const snapshot = await tx.get(ref);
    if (!snapshot.exists) throw new HttpsError('not-found', 'Report not found.');
    const report = snapshot.data();
    if (report.barangay !== request.auth.token.barangay) {
      throw new HttpsError('permission-denied', 'This report belongs to another barangay.');
    }
    const nextPriority = priority ?? report.priority;
    if (report.status === status && report.priority === nextPriority) return { changed: false };
    if (report.status !== status && !transitions[report.status]?.includes(status)) {
      throw new HttpsError('failed-precondition', `Cannot change ${report.status} to ${status}.`);
    }
    const version = (report.statusVersion || 0) + 1;
    const eventId = `${reportId}_${version}`;
    const now = FieldValue.serverTimestamp();
    tx.update(ref, { status, priority: nextPriority, updatedAt: now, statusVersion: version });
    tx.create(ref.collection('history').doc(String(version)), {
      previousStatus: report.status, status, priority: nextPriority,
      changedBy: request.auth.uid, createdAt: now,
    });
    if (report.ownerId) {
      tx.create(db.doc(`users/${report.ownerId}/notifications/${eventId}`), {
        reportId, title: 'Report updated',
        message: `Your ${report.category} report is ${status.toLowerCase()}. Priority: ${nextPriority}.`,
        read: false, createdAt: now,
      });
    }
    return { changed: true, status, priority: nextPriority };
  });
});

// Notification history is committed with the status update. Push is a delivery aid.
exports.sendStatusNotification = onDocumentCreated({
  document: 'users/{uid}/notifications/{notificationId}', retry: true,
}, async (event) => {
  if (!event.data || process.env.FUNCTIONS_EMULATOR === 'true') return;
  const db = database();
  const notice = event.data.data();
  const devices = await db.collection(`users/${event.params.uid}/devices`).limit(100).get();
  const valid = devices.docs.filter((doc) => typeof doc.data().token === 'string');
  if (!valid.length) return;
  const result = await getMessaging().sendEachForMulticast({
    tokens: valid.map((doc) => doc.data().token),
    notification: { title: notice.title, body: notice.message },
    data: { reportId: notice.reportId, notificationId: event.params.notificationId },
    android: { notification: { channelId: 'report_updates', tag: event.params.notificationId } },
  });
  const retryable = [];
  await Promise.all(result.responses.map(async (response, index) => {
    if (response.success) return;
    if (['messaging/registration-token-not-registered', 'messaging/invalid-registration-token']
      .includes(response.error?.code)) {
      await valid[index].ref.delete();
    } else {
      retryable.push(response.error?.code || 'unknown');
    }
  }));
  if (retryable.length) throw new Error(`FCM delivery retry required: ${retryable.join(',')}`);
});
