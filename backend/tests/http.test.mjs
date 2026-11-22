import { test } from 'node:test';
import assert from 'node:assert/strict';
import { smallText } from '../../supabase/functions/_shared/http.mjs';

test('webhook reads split UTF-8 faithfully before HMAC verification',async()=>{
  const bytes=new TextEncoder().encode('{"text":"PIN ROAD;Barangay;A pothole with £ sign."}');
  const stream=new ReadableStream({start(c){for(const byte of bytes)c.enqueue(Uint8Array.of(byte));c.close();}});
  assert.equal(await smallText(new Request('https://example.com',{method:'POST',body:stream,duplex:'half'})),new TextDecoder().decode(bytes));
});
test('webhook rejects an oversized chunked body and cancels its unread remainder',async()=>{
  let cancelled=false;
  const stream=new ReadableStream({pull(c){c.enqueue(new Uint8Array(1025));},cancel(){cancelled=true;}});
  const request=new Request('https://example.com',{method:'POST',body:stream,duplex:'half'});
  assert.equal(await smallText(request),null);
  assert.equal(cancelled,true);
});
