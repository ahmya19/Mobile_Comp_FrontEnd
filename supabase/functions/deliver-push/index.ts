import { createClient } from 'npm:@supabase/supabase-js@2.99.2';
import { importPKCS8, SignJWT } from 'npm:jose@6.1.0';
import { smallText } from '../_shared/http.mjs';

let oauth: {token:string;expires:number}|null=null;
async function googleToken(service: {client_email:string;private_key:string}) {
  if(oauth && oauth.expires>Date.now()+60000) return oauth.token;
  const key=await importPKCS8(service.private_key,'RS256');
  const assertion=await new SignJWT({scope:'https://www.googleapis.com/auth/firebase.messaging'})
    .setProtectedHeader({alg:'RS256'}).setIssuer(service.client_email).setAudience('https://oauth2.googleapis.com/token')
    .setIssuedAt().setExpirationTime('1h').sign(key);
  const response=await fetch('https://oauth2.googleapis.com/token',{method:'POST',body:new URLSearchParams({
    grant_type:'urn:ietf:params:oauth:grant-type:jwt-bearer',assertion})});
  const result=await response.json();
  if(!response.ok) throw new Error('FCM server authentication failed.');
  oauth={token:result.access_token,expires:Date.now()+result.expires_in*1000};
  return oauth.token;
}

Deno.serve(async (request: Request) => {
  if(request.method!=='POST') return new Response('POST required',{status:405});
  const server=createClient(Deno.env.get('SUPABASE_URL')!,Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!);
  const dispatchToken=request.headers.get('x-pinpoint-dispatch');
  let reportId:string|null=null;
  if(dispatchToken!==null) {
    if(!/^[0-9a-f]{64}$/.test(dispatchToken)) return Response.json({error:'Invalid dispatcher.'},{status:401});
    const {data:allowed,error}=await server.rpc('authorize_push_dispatch',{p_token:dispatchToken});
    if(error || allowed!==true) return Response.json({error:'Invalid dispatcher.'},{status:401});
  } else {
    const authorization=request.headers.get('Authorization') || '';
    const publicClient=createClient(Deno.env.get('SUPABASE_URL')!,Deno.env.get('SUPABASE_ANON_KEY')!,{global:{headers:{Authorization:authorization}}});
    const {data:identity,error:identityError}=await publicClient.auth.getUser(authorization.replace(/^Bearer\s+/i,''));
    if(identityError || !identity.user) return Response.json({error:'Sign in first.'},{status:401});
    const raw=await smallText(request).catch(()=>null);
    if(raw===null) return Response.json({error:'Invalid or oversized body.'},{status:413});
    const body=(()=>{try{return JSON.parse(raw);}catch(_){return null;}})();
    if(!body || typeof body.report_id!=='string' || !/^[0-9a-f-]{36}$/.test(body.report_id)) return Response.json({error:'Valid report_id required.'},{status:400});
    const {data:profile}=await publicClient.rpc('my_profile');
    const {data:report}=await publicClient.from('reports').select('barangay').eq('id',body.report_id).maybeSingle();
    if(!profile?.official || !report || report.barangay!==profile.barangay) return Response.json({error:'Assigned official required.'},{status:403});
    reportId=body.report_id;
  }
  const configured=Deno.env.get('FCM_SERVICE_ACCOUNT');
  if(!configured) return Response.json({error:'Push credentials are not configured; notification history remains available.'},{status:503});
  try {
    const service=JSON.parse(configured);
    const token=await googleToken(service);
    const {data:batch,error}=await server.rpc('claim_push_batch',{p_report_id:reportId});
    if(error) throw error;
    let delivered=0;
    let retryRequired=false;
    for(const notice of batch || []) {
      let failed=false;
      for(const device of notice.devices || []) {
        const response=await fetch(`https://fcm.googleapis.com/v1/projects/${service.project_id}/messages:send`,{
          method:'POST',headers:{Authorization:`Bearer ${token}`,'Content-Type':'application/json'},body:JSON.stringify({message:{
            token:device.token,
            data:{reportId:notice.report_id,notificationId:notice.notification_id,userId:device.user_id,
              title:'Report updated',body:notice.message},
            android:{priority:'HIGH'}
          }})});
        if(!response.ok) {
          const result=await response.json().catch(()=>({}));
          const invalid=result.error?.details?.some((detail:{errorCode?:string})=>detail.errorCode==='UNREGISTERED');
          if(invalid) await server.rpc('remove_invalid_push_token',{p_token:device.token});
          else failed=true;
        }
      }
      const {error:finishError}=await server.rpc('finish_push',{p_notification_id:notice.notification_id,p_lease_token:notice.lease_token,
        p_error:failed?'Some devices could not be reached; retry delivery.':null});
      if(finishError) throw finishError;
      retryRequired ||= failed;
      if(!failed) delivered++;
    }
    return Response.json({delivered,...(retryRequired?{error:'Some devices could not be reached; retry delivery.'}:{})},
      {status:retryRequired?503:200});
  } catch(_) { return Response.json({error:'Push delivery failed; notification history is retained.'},{status:503}); }
});
