const { before, after, test } = require('node:test');
const assert = require('node:assert/strict');
const { createRequire } = require('node:module');
const backendRequire = createRequire(require.resolve('../functions/package.json'));
const { initializeApp: initializeAdmin, deleteApp: deleteAdmin } = backendRequire('firebase-admin/app');
const { getAuth: adminAuth } = backendRequire('firebase-admin/auth');
const { getFirestore, FieldValue } = backendRequire('firebase-admin/firestore');
const { initializeApp, deleteApp } = require('firebase/app');
const { getAuth, connectAuthEmulator, signInWithEmailAndPassword } = require('firebase/auth');
const { getFunctions, connectFunctionsEmulator, httpsCallable } = require('firebase/functions');

let admin;
const apps = [];
const clients = {};
before(async () => {
  assert.ok(process.env.FIRESTORE_EMULATOR_HOST, 'Run with firebase emulators:exec');
  assert.ok(process.env.FIREBASE_AUTH_EMULATOR_HOST, 'Auth emulator is required');
  admin = initializeAdmin({ projectId: 'demo-pinpoint' }, 'functions-test-admin');
  for (const [uid, claims] of Object.entries({
    citizen: {}, official: { official: true, barangay: 'Alangilan' },
    outsider: { official: true, barangay: 'Other Barangay' },
  })) {
    const email = `${uid}@pinpoint.test`;
    try { await adminAuth(admin).deleteUser(uid); } catch (error) { if (error.code !== 'auth/user-not-found') throw error; }
    await adminAuth(admin).createUser({ uid, email, password: 'PinpointTest123!' });
    await adminAuth(admin).setCustomUserClaims(uid, claims);
    const app = initializeApp({ projectId: 'demo-pinpoint', apiKey: 'demo-api-key', appId: 'demo-app' }, uid);
    apps.push(app);
    const auth = getAuth(app);
    connectAuthEmulator(auth, `http://${process.env.FIREBASE_AUTH_EMULATOR_HOST}`, { disableWarnings: true });
    await signInWithEmailAndPassword(auth, email, 'PinpointTest123!');
    const functions = getFunctions(app, 'asia-southeast1');
    connectFunctionsEmulator(functions, '127.0.0.1', 5001);
    clients[uid] = httpsCallable(functions, 'updateReportStatus');
  }
  await getFirestore(admin).doc('reports/function-test').set({
    ownerId: 'citizen', category: 'Road Damage', barangay: 'Alangilan',
    status: 'Pending', priority: 'Low', createdAt: FieldValue.serverTimestamp(),
  });
});
after(async () => { await Promise.all(apps.map(deleteApp)); if (admin) await deleteAdmin(admin); });

test('callable denies citizens and officials from another barangay', async () => {
  const input = { reportId: 'function-test', status: 'Under Review' };
  await assert.rejects(clients.citizen(input), { code: 'functions/permission-denied' });
  await assert.rejects(clients.outsider(input), { code: 'functions/permission-denied' });
});

test('authorized status update atomically records history and notification; retry is idempotent', async () => {
  const input = { reportId: 'function-test', status: 'Under Review', priority: 'High' };
  const result = await clients.official(input);
  assert.equal(result.data.changed, true);
  const db = getFirestore(admin);
  assert.equal((await db.doc('reports/function-test').get()).data().status, 'Under Review');
  assert.equal((await db.doc('reports/function-test/history/1').get()).data().changedBy, 'official');
  const notices = await db.collection('users/citizen/notifications').get();
  assert.equal(notices.size, 1);
  assert.equal(notices.docs[0].data().read, false);
  assert.equal((await clients.official(input)).data.changed, false);
  assert.equal((await db.collection('users/citizen/notifications').get()).size, 1);
});

test('callable rejects illegal transitions and invalid priority', async () => {
  await assert.rejects(clients.official({ reportId: 'function-test', status: 'Pending' }), { code: 'functions/failed-precondition' });
  await assert.rejects(clients.official({ reportId: 'function-test', status: 'Resolved', priority: 'Critical' }), { code: 'functions/invalid-argument' });
});
