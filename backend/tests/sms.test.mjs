import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createHmac} from 'node:crypto';
import {parseSms,verifyGateway} from '../../supabase/functions/_shared/sms.mjs';

test('SMS parser maps category codes and trims fields',()=>{
  const result=parseSms('PIN ROAD; Alangilan ; A large pothole near the school.');
  assert.equal(result.category,'Road Damage');assert.equal(result.barangay,'Alangilan');
});
test('SMS parser rejects unsupported encoding, oversized messages and invalid content',()=>{
  for(const text of ['PIN ROAD;Alangilan;Too short','PIN INVALID;Alangilan;A long enough description.',
    'PIN ROAD;Alangilan;Danger here 🚧','PIN ROAD;Alangilan;'+ 'x'.repeat(160)]) assert.throws(()=>parseSms(text));
  // GSM extension characters consume two septets, not one.
  assert.throws(()=>parseSms('PIN ROAD;Alangilan;'+ '^'.repeat(80)));
});
test('gateway validates HMAC, clock window, configured gateway and tamper protection',async()=>{
  const secret='a-strong-test-only-secret-with-more-than-32-characters';
  const raw=JSON.stringify({text:'PIN ROAD;Alangilan;A large pothole near the school.'});
  const signature=createHmac('sha256',secret).update(`gateway-1.msg-1.1795250000.${raw}`).digest('hex');
  const headers=new Headers({'x-gateway-id':'gateway-1','x-message-id':'msg-1','x-timestamp':'1795250000','x-signature':signature});
  assert.equal((await verifyGateway(raw,headers,secret,'gateway-1',1795250001)).messageId,'msg-1');
  await assert.rejects(verifyGateway(raw+' ',headers,secret,'gateway-1',1795250001));
  await assert.rejects(verifyGateway(raw,headers,secret,'gateway-1',1795250400));
  await assert.rejects(verifyGateway(raw,headers,secret,'another-gateway',1795250001));
});
