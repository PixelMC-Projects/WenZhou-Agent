import React, { useEffect, useState } from 'react';
import { request } from './api.js';
import { formatTime } from './data.js';
import ModelSettings from './ModelSettings.jsx';
import ChatGptSettings from './ChatGptSettings.jsx';

export default function Service({credential,reasoningEffort,quotaEnabled,weeklyQuotaNanoYuan,resetRevision,toast,setUnsaved,update,gameConnected,serviceRevision}) {
  const [modelDirty,setModelDirty]=useState(false);

  useEffect(()=>{
    setUnsaved(modelDirty);
    return ()=>setUnsaved(false);
  },[modelDirty,setUnsaved]);

  return <div className="settings-scroll"><div className="settings-content">
    <ChatGptSettings online={gameConnected} revision={serviceRevision} toast={toast}/>
    <ModelSettings credential={credential} updatedAt={formatTime(credential?.updatedAt)}
      enabled={quotaEnabled} limit={weeklyQuotaNanoYuan} resetRevision={resetRevision} onDirty={setModelDirty} toast={toast}
      terraApi={request} terraRevision={serviceRevision}
      online={gameConnected} effort={reasoningEffort==='medium'?'high':reasoningEffort || 'low'}
      onEffort={async value=>update(await request('reasoning.set',{value}))}
      onCredential={async key=>update(await request(key===null?'credential.remove':'credential.set',key===null?{}:{key}))}
      onQuota={async body=>update(await request('quota.set',body))}
      onReset={async body=>{
        const result=await request('quota.resetall',body);
        update({resetRevision:result.resetRevision});
        return result;
      }}/>
  </div></div>;
}
