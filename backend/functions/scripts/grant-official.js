// Run only on the trusted backend machine using Application Default Credentials.
// Example: npm run grant-official -- PROJECT_ID USER_UID "Alangilan"
const { initializeApp, applicationDefault } = require('firebase-admin/app');
const { getAuth } = require('firebase-admin/auth');

async function main() {
  const [projectId, uid, barangay] = process.argv.slice(2);
  if (!projectId || !uid || !barangay || barangay.trim().length < 2) {
    throw new Error('Usage: npm run grant-official -- PROJECT_ID USER_UID "Barangay"');
  }
  initializeApp({ projectId, credential: applicationDefault() });
  const user = await getAuth().getUser(uid);
  await getAuth().setCustomUserClaims(uid, {
    ...user.customClaims, official: true, barangay: barangay.trim(),
  });
  console.log('Official claim assigned. Sign out and back in on the phone to refresh the token.');
}
main().catch((error) => { console.error(error.message); process.exitCode = 1; });
