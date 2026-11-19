import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { Confirm, ErrorNotice, Switch } from '@manager-console/titles/ui.jsx';
import { request } from './api.js';
import { formatTime } from './data.js';
import ModelSettings from './ModelSettings.jsx';
import AgreementEditor from './AgreementEditor.jsx';

export default function Service({policy,credential,reasoningEffort,quotaEnabled,weeklyQuotaNanoYuan,resetRevision,showAgreement,toast,setUnsaved,update,tab,gameConnected}) {
  const [base,setBase]=useState(policy);
  const [text,setText]=useState(policy.draft || policy.text);
  const [error,setError]=useState('');
  const [confirm,setConfirm]=useState('');
  const [busy,setBusy]=useState(false);

  const editField = useCallback((field,doc) => {
    setText(current => ({...current,[field]:doc}));
  }, []);
  const dirty = useMemo(() => {
    const saved = base.draft || base.text;
    return Object.keys(text).some(field => text[field] !== saved[field]
      && JSON.stringify(text[field]) !== JSON.stringify(saved[field]));
  }, [text,base]);
  const [modelDirty,setModelDirty]=useState(false);
  const hasChanges=dirty || modelDirty;
  const savedDraft=useMemo(() => Boolean(base.draft) && JSON.stringify(base.text)!==JSON.stringify(base.draft), [base]);
  const conflict=base.revision!==policy.revision && dirty;
  function load(next) {
    setBase(next);
    setText(next.draft || next.text);
    setError('');
  }
  useEffect(()=>{
    if (!dirty && base.revision!==policy.revision) load(policy);
  },[policy.revision,base.revision,dirty]);
  useEffect(()=>{
    setUnsaved(hasChanges);
    return ()=>setUnsaved(false);
  },[hasChanges,setUnsaved]);
  async function save(publish=false) {
    setBusy(true);
    try {
      const next=await request(publish ? 'policy.publish' : 'policy.draft', publish ? {revision:base.revision} : {text,revision:base.revision});
      update(next); load(next.policy);
      toast(publish?'新版协议已发布':'协议草稿已保存');
    } catch (e) {
      setError(e.message);
      try { update(await request('status')); } catch { /* Keep the original save error visible. */ }
    }
    finally { setBusy(false);setConfirm(''); }
  }
  async function changeVisibility(enabled) {
    setBusy(true);
    try {
      update(await request('policy.visibility', {enabled}));
      setError('');
      toast(enabled ? '已向玩家展示协议' : '已隐藏玩家端协议');
    } catch (e) { setError(e.message); }
    finally { setBusy(false); }
  }

  return <>
    <div className="settings-scroll"><div className="settings-content">
      <ErrorNotice error={error}/>
      <ModelSettings hidden={tab!=='key'} credential={credential} updatedAt={formatTime(credential?.updatedAt)}
        enabled={quotaEnabled} limit={weeklyQuotaNanoYuan} resetRevision={resetRevision} onDirty={setModelDirty} toast={toast}
        online={gameConnected} effort={reasoningEffort==='medium'?'high':reasoningEffort || 'low'}
        onEffort={async value=>update(await request('reasoning.set',{value}))}
        onCredential={async key=>update(await request(key===null?'credential.remove':'credential.set',key===null?{}:{key}))}
        onQuota={async body=>update(await request('quota.set',body))}
        onReset={async body=>{
          const result=await request('quota.resetall',body);
          update({resetRevision:result.resetRevision});
          return result;
        }}/>
      {tab!=='key' && <><section className="settings-section">
        <Switch label="向玩家展示用户协议" value={showAgreement} disabled={!gameConnected||busy} onChange={changeVisibility}
          description="同时控制首次进入时的协议页和输入栏下方的协议链接。切换后立即生效，玩家已有的同意记录会保留。"/>
      </section>
      <section className="settings-section"><div className="settings-section-heading"><div><h2>问舟内测使用协议</h2>
        <p>当前生效版本 {policy.versionLabel || policy.version}。草稿仅管理员可见。发布新版后，开启协议展示时，玩家须确认新版本。</p></div></div>
        {Object.entries({rules:'内测滥用规则',brief:'协议简版',details:'完整说明'}).map(([id,label])=>(
          <AgreementEditor key={id+'-'+base.revision} field={id} label={label} value={text[id]}
            onChange={editField} readOnly={!gameConnected}/>
        ))}
      </section>
      <div className="settings-save"><span>{conflict?'另一窗口已修改协议':dirty?'有未保存的修改':savedDraft?'草稿已保存，尚未发布':'当前设置已保存'}</span>
        {conflict&&<button className="button secondary" onClick={()=>load(policy)}>重新载入</button>}
        <button className="button secondary" disabled={!gameConnected||busy||!dirty||conflict} onClick={()=>save()}>保存草稿</button>
        <button className="button primary" disabled={!gameConnected||busy||dirty||!savedDraft} onClick={()=>setConfirm('publish')}>发布新版</button>
      </div></>}
    </div></div>
    {confirm==='publish'&&<Confirm title="发布新版用户协议？" subtitle="WenzhouAI" label="确认发布" confirm={()=>{if(gameConnected) save(true);}} cancel={()=>setConfirm('')} busy={busy||!gameConnected}>保存的草稿将成为当前版本。协议展示开启时，已确认旧版的玩家需要重新同意。</Confirm>}
  </>;
}



