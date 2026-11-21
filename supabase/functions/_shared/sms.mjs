const categories = Object.freeze({ROAD:'Road Damage',LIGHT:'Streetlight',FLOOD:'Flooding',GARBAGE:'Garbage',WATER:'Water Interruption',OTHER:'Other'});
const extended=new Set([...'^{}\\[~]|€']);
const basic=new Set([..."@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà"]);

export function parseSms(text) {
  if(typeof text!=='string') throw new Error('SMS text is required.');
  let septets=0;
  for(const char of text) {
    if(extended.has(char)) septets+=2;
    else if(basic.has(char)) septets++;
    else throw new Error('Use GSM-compatible text for a single SMS.');
  }
  if(septets>160) throw new Error('Message exceeds one 160-septet SMS.');
  const match=/^PIN\s+(ROAD|LIGHT|FLOOD|GARBAGE|WATER|OTHER);([^;\r\n]+);([^\r\n]+)$/i.exec(text.trim());
  if(!match) throw new Error('Use PIN ROAD;Barangay;Description (or LIGHT, FLOOD, GARBAGE, WATER, OTHER).');
  const barangay=match[2].trim(),description=match[3].trim();
  if(barangay.length<2 || barangay.length>100 || description.length<10 || description.length>2000) throw new Error('Provide a barangay and a description of at least 10 characters.');
  return {category:categories[match[1].toUpperCase()],barangay,description,septets};
}

export async function verifyGateway(rawBody,headers,secret,allowedGateway,nowSeconds=Math.floor(Date.now()/1000)) {
  if(!secret || secret.length<32 || !allowedGateway) throw new Error('SMS gateway is not configured.');
  const gateway=headers.get('x-gateway-id'),messageId=headers.get('x-message-id');
  const timestamp=headers.get('x-timestamp'),signature=headers.get('x-signature');
  if(gateway!==allowedGateway || !/^[A-Za-z0-9_-]{1,64}$/.test(gateway || '') ||
    !/^[A-Za-z0-9_.:-]{1,128}$/.test(messageId || '') || !/^\d{10}$/.test(timestamp || '') ||
    Math.abs(nowSeconds-Number(timestamp))>300 || !/^[0-9a-f]{64}$/i.test(signature || '')) throw new Error('Invalid or expired gateway signature.');
  const key=await crypto.subtle.importKey('raw',new TextEncoder().encode(secret),{name:'HMAC',hash:'SHA-256'},false,['verify']);
  const bytes=Uint8Array.from(signature.match(/../g),hex=>parseInt(hex,16));
  const payload=new TextEncoder().encode(`${gateway}.${messageId}.${timestamp}.${rawBody}`);
  if(!await crypto.subtle.verify('HMAC',key,bytes,payload)) throw new Error('Invalid or expired gateway signature.');
  return {gateway,messageId};
}
