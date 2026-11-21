import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createHmac} from 'node:crypto';
import {parseSms,verifyGateway,verifySmsGate} from '../../supabase/functions/_shared/sms.mjs';

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

test('SMS Gateway for Android adapter verifies body+timestamp HMAC and the documented event shape',async()=>{
  const secret='a-strong-test-only-secret-with-more-than-32-characters',device='ffffffffceb0b1db0000018e937c815b';
  const raw=JSON.stringify({deviceId:device,event:'sms:received',id:'delivery-1',webhookId:'w1',
    payload:{messageId:'abc123',message:'PIN WATER;Alangilan;No water since morning.',sender:'+639170000000',recipient:null,simNumber:1,receivedAt:'2026-11-21T15:46:11.000+08:00'}});
  const sign=(body,ts)=>createHmac('sha256',secret).update(body+ts).digest('hex');
  const headers=(body,ts='1795250000')=>new Headers({'x-timestamp':ts,'x-signature':sign(body,ts)});
  const ok=await verifySmsGate(raw,headers(raw),secret,device,1795250001);
  assert.equal(ok.messageId,'abc123');assert.equal(parseSms(ok.text).category,'Water Interruption');
  // A retried delivery has a new id but must hash identically.
  const retry=raw.replace('delivery-1','delivery-2');
  assert.equal((await verifySmsGate(retry,headers(retry),secret,device,1795250001)).hashInput,ok.hashInput);
  await assert.rejects(verifySmsGate(raw+' ',headers(raw),secret,device,1795250001));          // tampered body
  await assert.rejects(verifySmsGate(raw,headers(raw),secret,device,1795250400));              // stale
  await assert.rejects(verifySmsGate(raw,headers(raw),secret,'another-device',1795250001));    // wrong device
  const other=raw.replace('sms:received','sms:sent');
  await assert.rejects(verifySmsGate(other,headers(other),secret,device,1795250001));          // wrong event
  await assert.rejects(verifySmsGate(raw,headers(raw),'short',device,1795250001));             // weak secret
});
