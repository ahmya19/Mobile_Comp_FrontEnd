import { createClient } from 'npm:@supabase/supabase-js@2.99.2';
import { parseSms, verifyGateway } from '../_shared/sms.mjs';
import { smallText } from '../_shared/http.mjs';

Deno.serve(async (request: Request) => {
  if(request.method!=='POST') return new Response('POST required',{status:405});
  // Gateway envelopes are intentionally small; never accept an arbitrary upload.
  try {
    const raw=await smallText(request);
    if(raw===null) return new Response('Payload too large',{status:413});
    const envelope=await verifyGateway(raw,request.headers,Deno.env.get('SMS_GATEWAY_SECRET'),Deno.env.get('SMS_GATEWAY_ID'));
    const body=JSON.parse(raw);
    if(Object.keys(body).some(key=>key!=='text')) throw new Error('Only the text field is accepted.');
    const report=parseSms(body.text);
    const hash=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(raw))))
      .map(value=>value.toString(16).padStart(2,'0')).join('');
    const db=createClient(Deno.env.get('SUPABASE_URL')!,Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!);
    const {data,error}=await db.rpc('ingest_sms_report',{p_gateway_id:envelope.gateway,p_message_id:envelope.messageId,
      p_payload_hash:hash,p_category:report.category,p_description:report.description,p_barangay:report.barangay});
    if(error) return Response.json({error:error.message},{status:400});
    return Response.json({report_id:data});
  } catch(error) {
    return Response.json({error:error instanceof Error?error.message:'Invalid gateway request'},{status:400});
  }
});
