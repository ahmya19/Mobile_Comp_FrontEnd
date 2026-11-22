/** Read no more than the allowed bytes, even without Content-Length. */
export async function smallText(request,maxBytes=2048) {
  if(Number(request.headers.get('content-length') || 0)>maxBytes) return null;
  const reader=request.body?.getReader();
  if(!reader) return '';
  const chunks=[];let length=0;
  try {
    while(true) {
      const {done,value}=await reader.read();
      if(done) break;
      length+=value.length;
      if(length>maxBytes) { await reader.cancel();return null; }
      chunks.push(value);
    }
  } finally { reader.releaseLock(); }
  const bytes=new Uint8Array(length);let offset=0;
  for(const chunk of chunks) { bytes.set(chunk,offset);offset+=chunk.length; }
  return new TextDecoder('utf-8',{fatal:true}).decode(bytes);
}
