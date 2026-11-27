import React, {useEffect, useState} from 'react';
import {Confirm, ErrorNotice, Select, Switch} from '@manager-console/titles/ui.jsx';
import {AmountInput, inputAmount, parseAmount} from './Quota.jsx';
import {TerraModelFields, TerraQuotaFields, useTerraSettings} from './Terra.jsx';

const efforts = [
  {value:'off',label:'关闭'}, {value:'low',label:'低'}, {value:'high',label:'高'}, {value:'max',label:'最高'},
];

export default function ModelSettings({hidden, preview=false, online, credential, updatedAt, effort,
  enabled, limit, resetRevision, onEffort, onCredential, onQuota, onReset, onDirty, toast, terraApi, terraRevision, onPreviewBillingHold}) {
  const terra = useTerraSettings(terraApi, terraRevision);
  const [effortDraft,setEffort]=useState(null);
  const [enabledDraft,setEnabled]=useState(null);
  const [amountDraft,setAmount]=useState(null);
  const [key,setKey]=useState('');
  const [remove,setRemove]=useState(false);
  const [confirm,setConfirm]=useState('');
  const [busy,setBusy]=useState(false);
  const [error,setError]=useState('');
  const level=effortDraft ?? effort;
  const on=enabledDraft ?? enabled;
  const amountText=amountDraft ?? inputAmount(limit);
  const amount=parseAmount(amountText);
  const changedQuota=on!==enabled || (amountDraft!==null && amount!==limit);
  const changedLive=remove || Boolean(key) || level!==effort;
  const dirty=changedLive || changedQuota || terra.dirty;
  const ready=!busy && dirty && (!changedLive || online) && (!key || key.trim()) && (!changedQuota || amount!==null)
    && (!terra.dirty || terra.ready);
  useEffect(()=>{ onDirty(dirty); return ()=>onDirty(false); },[dirty,onDirty]);
  useEffect(()=>{ if (confirm==='remove' && terra.conflict) setConfirm(''); },[confirm,terra.conflict]);

  async function save() {
    setBusy(true); setError(''); setConfirm('');
    try {
      if (terra.dirty && !await terra.save()) return;
      if (level!==effort) { await onEffort(level); setEffort(null); }
      if (changedQuota) { await onQuota({quotaEnabled:on,weeklyQuotaNanoYuan:amount}); setEnabled(null); setAmount(null); }
      if (key || remove) { await onCredential(remove ? null : key.trim()); setKey(''); setRemove(false); }
      toast('设置已保存');
    } catch(e) { setError(e.message); }
    finally { setBusy(false); }
  }
  async function reset() {
    setBusy(true); setError('');
    try {
      const result=await onReset({resetRevision});
      toast(result.affected ? '已重置 '+result.affected+' 位玩家的额度' : '当前没有需要重置的玩家');
    } catch(e) { setError(e.message); }
    finally { setBusy(false); setConfirm(''); }
  }
  return <div hidden={hidden}>
    <ErrorNotice error={error}/>
    <section className="settings-section">
      <div className="settings-section-heading"><div><h2>DeepSeek 接口</h2><p>配置问舟向官方 API 发起请求时使用的服务密钥。</p></div></div>
      <div className="setting-row"><div><strong>思考等级</strong><p>{preview ? '作为默认值。服主可在 HUB 单独调整自己的思考强度。' : '应用于所有玩家的下一次提问。'}</p></div>
        <Select label="全局思考等级" value={level} options={efforts} onChange={setEffort} disabled={busy || !online}/></div>
      <dl className="detail-definition"><div><dt>密钥状态</dt><dd>{remove?'待移除':credential?.configured?(preview?'已配置（模拟）':'已配置'):'未配置'}</dd></div>
        <div><dt>最近修改</dt><dd>{updatedAt}</dd></div></dl>
      <label className="form-group"><span>{credential?.configured?'替换 API Key':'API Key'}</span><input type="password" autoComplete="new-password"
        value={key} disabled={busy || !online || remove} onChange={e=>setKey(e.target.value)} placeholder={preview?'输入测试密钥':'输入 DeepSeek API Key'} maxLength={512}/></label>
      <p className="muted">{preview?'本地演示只记配置状态，不保存密钥原文或调用 API。请使用测试值。':'密钥由服务器保存。管理页只显示配置状态与修改时间。'}</p>
      {credential?.configured && <button className="button quiet" disabled={busy || !online} onClick={()=>{setRemove(!remove);setKey('');}}>{remove?'撤销移除':'移除密钥'}</button>}
    </section>
    <TerraModelFields settings={terra} disabled={busy} preview={preview} onPreviewBillingHold={onPreviewBillingHold} toast={toast}/>
    <section className="settings-section">
      <div className="settings-section-heading"><h2>使用限额</h2></div>
      <Switch label="启用 DeepSeek 个人限额" value={on} disabled={busy || !Number.isSafeInteger(limit)} onChange={setEnabled} description="DeepSeek 每期从首条提问起算七天，精确到分钟。"/>
      <div className="wza-quota-controls">
        <AmountInput label="DeepSeek 默认每人周限额" value={amountText} onChange={setAmount} disabled={busy || !Number.isSafeInteger(limit)}/>
        <button className="button quiet" disabled={busy || !Number.isSafeInteger(resetRevision)} onClick={()=>setConfirm('reset')}>重置所有 DeepSeek 玩家额度</button>
      </div>
      {amountDraft!==null && amount===null && <p className="muted">请输入有效金额，最多保留三位小数。</p>}
      <TerraQuotaFields settings={terra} disabled={busy}/>
    </section>
    <div className="settings-save"><span>{dirty?'有未保存的修改':'当前设置已保存'}</span>
      <button className="button primary" disabled={!ready} onClick={()=>remove || terra.remove ? setConfirm('remove') : save()}>保存设置</button></div>
    {confirm==='reset' && <Confirm title="重置所有 DeepSeek 玩家额度？" subtitle="问舟AI" label="确认重置" confirm={reset} cancel={()=>setConfirm('')} busy={busy}>
      所有玩家本期的 DeepSeek 已用额度将清零，从当前分钟重新计算七天。累计消耗和个人限额设置保留。
    </Confirm>}
    {confirm==='remove' && <Confirm title={terra.remove && !remove ? '移除 OpenAI 项目 API Key 并保存设置？' : '移除 API Key 并保存设置？'}
      subtitle="问舟AI" label="确认保存" danger confirm={()=>{if (ready) save();}} cancel={()=>setConfirm('')} busy={busy}>
      {remove && <p>移除 DeepSeek 密钥后，问舟将无法使用该密钥发起请求。</p>}
      {terra.remove && <p>{terra.credential?.hasExternal
        ? '移除网页密钥后，Manager 将读取环境配置中的 OpenAI 密钥。'
        : '移除网页密钥后，Terra 服务将暂停，直至重新配置 OpenAI 项目密钥。'}</p>}
    </Confirm>}
  </div>;
}
