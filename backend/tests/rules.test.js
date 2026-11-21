const { before, beforeEach, after, test } = require('node:test');
const { readFileSync } = require('node:fs');
const path = require('node:path');
const { initializeTestEnvironment, assertSucceeds, assertFails } = require('@firebase/rules-unit-testing');
const { doc, setDoc, getDoc, updateDoc, serverTimestamp, GeoPoint } = require('firebase/firestore');
const { ref, uploadBytes, getBytes } = require('firebase/storage');

let env;
const report = (extra = {}) => ({
  ownerId: 'alice', category: 'Road Damage', description: 'A large pothole near the school.',
  location: 'Main road', barangay: 'Alangilan', status: 'Pending', priority: 'Low',
  source: 'app', createdAt: serverTimestamp(), updatedAt: serverTimestamp(),
  photoPath: null, coordinates: new GeoPoint(13.78, 121.07), ...extra,
});
before(async () => {
  env = await initializeTestEnvironment({
    projectId: 'demo-pinpoint',
    firestore: { rules: readFileSync(path.join(__dirname, '../firestore.rules'), 'utf8') },
    storage: { rules: readFileSync(path.join(__dirname, '../storage.rules'), 'utf8') },
  });
});
beforeEach(async () => { await env.clearFirestore(); await env.clearStorage(); });
after(async () => { await env?.cleanup(); });

test('signed-out clients cannot read or create reports', async () => {
  const db = env.unauthenticatedContext().firestore();
  await assertFails(getDoc(doc(db, 'reports/test')));
  await assertFails(setDoc(doc(db, 'reports/test'), report()));
});

test('a citizen creates a valid report and other citizens can read the community feed', async () => {
  await assertSucceeds(setDoc(doc(env.authenticatedContext('alice').firestore(), 'reports/test'), report()));
  await assertSucceeds(getDoc(doc(env.authenticatedContext('bob').firestore(), 'reports/test')));
});

test('creation rejects spoofed ownership, elevated status, extra fields and invalid data', async () => {
  const db = env.authenticatedContext('alice').firestore();
  for (const extra of [
    { ownerId: 'bob' }, { status: 'Resolved' }, { priority: 'High' }, { source: 'sms' },
    { category: 'unknown' }, { description: 'short' }, { barangay: '' },
    { coordinates: '13,121' }, { photoPath: 'report-images/bob/test/photo.jpg' },
    { official: true }, { createdAt: new Date(0) },
  ]) await assertFails(setDoc(doc(db, 'reports/test'), report(extra)));
});

test('neither a citizen nor an official can bypass the status function', async () => {
  const alice = env.authenticatedContext('alice').firestore();
  await setDoc(doc(alice, 'reports/test'), report());
  await assertFails(updateDoc(doc(alice, 'reports/test'), { status: 'Resolved' }));
  const official = env.authenticatedContext('official', { official: true, barangay: 'Alangilan' }).firestore();
  await assertFails(updateDoc(doc(official, 'reports/test'), { status: 'Resolved' }));
});

test('profiles remain private and cannot grant official roles', async () => {
  const alice = env.authenticatedContext('alice').firestore();
  await assertSucceeds(setDoc(doc(alice, 'users/alice'), { displayName: 'Alice', updatedAt: serverTimestamp() }));
  await assertFails(getDoc(doc(env.authenticatedContext('bob').firestore(), 'users/alice')));
  await assertFails(updateDoc(doc(alice, 'users/alice'), { official: true }));
});

test('notification creation and content edits are server-only; owner can mark read', async () => {
  await env.withSecurityRulesDisabled(async (context) => {
    await setDoc(doc(context.firestore(), 'users/alice/notifications/n1'), { message: 'Under review', read: false });
  });
  const alice = env.authenticatedContext('alice').firestore();
  const notice = doc(alice, 'users/alice/notifications/n1');
  await assertSucceeds(updateDoc(notice, { read: true }));
  await assertFails(updateDoc(notice, { message: 'Forged notification' }));
  await assertFails(setDoc(doc(alice, 'users/alice/notifications/fake'), { read: false }));
  await assertFails(getDoc(doc(env.authenticatedContext('bob').firestore(), 'users/alice/notifications/n1')));
});

test('photo uploads enforce owner, MIME type and size; published evidence is immutable', async () => {
  const context = env.authenticatedContext('alice');
  const storage = context.storage('gs://demo-pinpoint.appspot.com');
  const photo = ref(storage, 'report-images/alice/test/photo.jpg');
  const bytes = new Uint8Array([255, 216, 255, 217]);
  await assertFails(uploadBytes(ref(storage, 'report-images/bob/test/photo.jpg'), bytes, { contentType: 'image/jpeg' }));
  await assertFails(uploadBytes(photo, bytes, { contentType: 'text/plain' }));
  await assertFails(uploadBytes(photo, new Uint8Array(256001), { contentType: 'image/jpeg' }));
  await assertSucceeds(uploadBytes(photo, bytes, { contentType: 'image/jpeg' }));
  await setDoc(doc(context.firestore(), 'reports/test'), report({ photoPath: 'report-images/alice/test/photo.jpg' }));
  await assertSucceeds(getBytes(photo));
  await assertFails(uploadBytes(photo, bytes, { contentType: 'image/jpeg' }));
});
