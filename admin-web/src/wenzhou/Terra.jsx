import React, {useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState} from 'react';
import {ErrorNotice, Select, Switch} from '@manager-console/titles/ui.jsx';
import {AmountInput, inputAmount, parseAmount} from './Quota.jsx';
import {formatTime} from './data.js';
import './terra.css';

const effortOptions = [
  {value:'high',label:'高'},
  {value:'xhigh',label:'极高'},
  {value:'max',label:'最高'},
];
const speedOptions = [{value:'standard',label:'标准'},{value:'fast',label:'高速'}];
const usd = new Intl.NumberFormat('en-US', {style:'currency', currency:'USD', minimumFractionDigits:0, maximumFractionDigits:3});
const percent = new Intl.NumberFormat('zh-CN', {maximumFractionDigits:1});
const asPercent = value => Number.isFinite(value) && value >= 0 ? percent.format(Math.min(100,value))+'%' : '—';
function asDollars(value) {
  if (!Number.isSafeInteger(value) || value < 0) return '—';
  return value > 0 && value < 1_000_000 ? '<$0.001' : usd.format(value / 1e9);
}

function validCredential(value) {
  return typeof value?.configured === 'boolean' && ['manager','environment','none','invalid'].includes(value.source)
    && typeof value.revision === 'string' && value.revision.length > 0;
}

function credentialSummary(value) {
  if (!validCredential(value)) return null;
  const {configured,source,revision,updatedAt,hasManaged,hasExternal} = value;
  return {configured,source,revision,updatedAt,hasManaged,hasExternal};
}

export function useTerraResource(api, playerId, revision) {
  const readOperation = playerId ? 'terra.account' : 'terra.settings';
  const writeOperation = playerId ? 'terra.grant' : 'terra.configure';
  const scope = useMemo(() => ({api,playerId}), [api,playerId]);
  const mounted = useRef(null);
  const pending = useRef(null);
  const refreshAfterWrite = useRef(false);
  const [snapshot,setSnapshot] = useState(null);

  useLayoutEffect(() => {
    mounted.current = scope;
    return () => {
      mounted.current = null;
      refreshAfterWrite.current = false;
      pending.current?.controller.abort();
      pending.current = null;
    };
  }, [scope]);

  const perform = useCallback(async function perform(operation = readOperation, changes = {}, previousError = '') {
    if (mounted.current !== scope) return null;
    const writing = operation !== readOperation;
    const credentialWrite = operation === 'terra.credential';
    const resume = operation === 'terra.resume';
    if (pending.current?.writing) {
      if (!writing) refreshAfterWrite.current = true;
      return null;
    }
    if (!writing) refreshAfterWrite.current = false;
    pending.current?.controller.abort();
    const attempt = {controller:new AbortController(),writing};
    pending.current = attempt;
    setSnapshot(current => ({scope,data:current?.scope === scope ? current.data : null,busy:true,error:'',forbidden:false}));
    const input = playerId ? {playerId,...changes} : changes;
    const current = () => mounted.current === scope && pending.current === attempt;

    try {
      const data = await api(operation,input,attempt.controller.signal);
      if (!current()) return null;
      if (credentialWrite) {
        const credential = credentialSummary(data);
        if (!credential) throw new Error('配置状态无效');
        setSnapshot(current => ({scope,data:{...current.data,credential},busy:false,error:'',forbidden:false}));
        return credential;
      }
      if (!Number.isSafeInteger(data?.revision) || data.revision < 0
          || (playerId ? typeof data.granted !== 'boolean'
            : typeof data.fast !== 'boolean' || !effortOptions.some(option => option.value === data.effort))) {
        throw new Error('Terra 返回的管理数据无效。');
      }
      const result = playerId ? data : {...data,credential:credentialSummary(data.credential)};
      setSnapshot({scope,data:result,busy:false,error:previousError,forbidden:false});
      return result;
    } catch (error) {
      if (!current()) return null;
      const forbidden = [401,403].includes(error.status);
      const message = resume
        ? error.status === 409 ? 'OpenAI 项目额度状态已变更，请检查最新状态。' : '未能恢复 Terra 使用，请检查项目配置后重试。'
        : credentialWrite
          ? error.status === 409 ? 'OpenAI 密钥状态已更新，请重新输入密钥或确认移除。' : '未能保存 OpenAI 密钥，请重试。'
          : error.status === 409 ? 'Terra 设置已变更，已读取最新状态。' : error.message;
      if (writing && !forbidden && (resume || error.status === 409 || !error.status || error.status >= 500)) {
        pending.current = null;
        await perform(readOperation, {}, message);
        return null;
      }
      setSnapshot(current => ({scope,data:writing && !forbidden ? current.data : null,busy:false,error:message,forbidden}));
      return null;
    } finally {
      if (current()) {
        pending.current = null;
        if (refreshAfterWrite.current) void perform();
      }
    }
  }, [api,playerId,scope,readOperation]);

  const read = useCallback(() => {
    if (!document.hidden) void perform();
  }, [perform]);
  useEffect(() => {
    read();
    document.addEventListener('visibilitychange',read);
    return () => document.removeEventListener('visibilitychange',read);
  }, [read,revision]);

  return {
    ...(snapshot?.scope === scope ? snapshot : {data:null,busy:true,error:'',forbidden:false}),
    read,
    save:changes=>perform(writeOperation,changes),
    saveCredential:changes=>perform('terra.credential',changes),
    saveQuota:changes=>perform('terra.quota',changes),
    resume:changes=>perform('terra.resume',changes),
  };
}

const quotaFields = [
  ['weeklyLimitNanoUsd','GPT 默认每人周限额'],
  ['monthlyLimitNanoUsd','GPT 默认每人月限额'],
  ['serverMonthlyLimitNanoUsd','GPT 全服月预算'],
];
const quotaAmount = (text,saved) => Number.isSafeInteger(saved) && text===inputAmount(saved) ? saved : parseAmount(text);
const limitModes = [
  {value:'inherit',label:'继承全局'},
  {value:'custom',label:'独立额度'},
  {value:'unlimited',label:'不限额'},
];

export function PlayerTerraAccess({api, playerId, granted, disabled, revision, toast}) {
  const {data,busy,error,read,save,saveQuota} = useTerraResource(api, playerId, revision);
  const canChange = !disabled && !busy && Boolean(data && (granted || data.granted));

  async function change(allowed) {
    if (!canChange || (allowed && !granted)) return;
    const next = await save({allowed,revision:data.revision});
    if (next) toast(next.granted ? '已授予 Terra 使用权限' : '已撤销 Terra 使用权限');
  }

  const limits = data && Number.isSafeInteger(data.weeklyLimitNanoUsd) && Number.isSafeInteger(data.monthlyLimitNanoUsd)
    && (data.personalUnlimited ? '个人额度不限。' : '每位玩家每周 '+asDollars(data.weeklyLimitNanoUsd)+'，每月 '+asDollars(data.monthlyLimitNanoUsd)+'。');
  const description = [!granted && '需先允许访问问舟。',limits].filter(Boolean).join('');
  return <div className="wza-terra-access">
    <Switch label="允许使用 Terra" value={data?.granted} onChange={change}
      disabled={!canChange} description={description}/>
    {granted && data?.mode && <PlayerTerraQuota account={data} disabled={disabled || busy} save={saveQuota} toast={toast}/>}
    {granted && data?.granted && <dl className="detail-definition">
      <div><dt>当前可用额度</dt><dd>{data.unlimited ? '不限额' : data.enabled === false ? '未启用' : asPercent(data.remainingPercent)}</dd></div>
      {!data.personalUnlimited && <><div><dt>本周剩余</dt><dd>{asDollars(data.weeklyRemainingNanoUsd)} / {asDollars(data.weeklyLimitNanoUsd)}
        {data.weeklyResetAt > 0 && <small> · {formatTime(data.weeklyResetAt)} 重置</small>}</dd></div>
      <div><dt>本月剩余</dt><dd>{asDollars(data.monthlyRemainingNanoUsd)} / {asDollars(data.monthlyLimitNanoUsd)}
        {data.monthlyResetAt > 0 && <small> · {formatTime(data.monthlyResetAt)} 重置</small>}</dd></div></>}
      {data.serverQuotaEnabled && <div><dt>全服月预算剩余</dt><dd>{asDollars(data.serverMonthlyRemainingNanoUsd)} / {asDollars(data.serverMonthlyLimitNanoUsd)}
        {data.serverMonthlyResetAt > 0 && <small> · {formatTime(data.serverMonthlyResetAt)} 重置</small>}</dd></div>}
      {data.exhausted && data.resetAt > 0 && <div><dt>预计恢复可用时间（北京时间）</dt><dd>{formatTime(data.resetAt)}</dd></div>}
    </dl>}
    {!data && busy && <p className="muted" role="status">正在读取 Terra 权限…</p>}
    <ErrorNotice error={error} retry={!data && !busy ? read : undefined}/>
  </div>;
}

function PlayerTerraQuota({account, disabled, save, toast}) {
  const [editing,setEditing] = useState(null);
  const weekly = account.customWeeklyLimitNanoUsd ?? account.weeklyLimitNanoUsd;
  const monthly = account.customMonthlyLimitNanoUsd ?? account.monthlyLimitNanoUsd;
  const draft = editing ?? {mode:account.mode, weekly:inputAmount(weekly), monthly:inputAmount(monthly), revision:account.revision};
  const custom = draft.mode === 'custom';
  const week = quotaAmount(draft.weekly,weekly);
  const month = quotaAmount(draft.monthly,monthly);
  const invalid = custom && (week === null || month === null);
  const dirty = draft.mode !== account.mode || (custom && (week !== weekly || month !== monthly));
  const conflict = dirty && draft.revision !== account.revision;
  const locked = disabled || conflict;
  function edit(fields) { setEditing({...draft,...fields}); }

  async function submit() {
    const body = {mode:draft.mode,revision:draft.revision};
    if (custom) {
      body.customWeeklyLimitNanoUsd = week;
      body.customMonthlyLimitNanoUsd = month;
    }
    if (await save(body)) {
      setEditing(null);
      toast('玩家 Terra 限额已保存');
    }
  }

  return <div className="wza-terra-player-quota">
    <div className="setting-row">
      <div><strong>Terra 个人额度</strong><p>{account.quotaEnabled === false
        ? '全局个人限额已关闭。启用的全服月预算仍有效。'
        : '启用的全服月预算适用于所有玩家。'}</p></div>
      <Select label="Terra 个人额度" value={draft.mode} options={limitModes} onChange={mode=>edit({mode})} disabled={locked}/>
    </div>
    {custom && <div className="wza-quota-controls">
      <AmountInput label="Terra 个人周限额" unit="USD" value={draft.weekly} onChange={weekly=>edit({weekly})} disabled={locked}/>
      <AmountInput label="Terra 个人月限额" unit="USD" value={draft.monthly} onChange={monthly=>edit({monthly})} disabled={locked}/>
    </div>}
    {invalid && <p className="muted">请输入有效美元金额，最多三位小数。</p>}
    {conflict && <p className="muted">这位玩家的 Terra 设置已在其他位置修改。</p>}
    {dirty && <div className="inline-actions">
      <button className="button secondary" disabled={locked || invalid} onClick={submit}>保存 Terra 个人额度</button>
      <button className="button quiet" disabled={disabled} onClick={()=>setEditing(null)}>{conflict?'载入最新额度':'取消修改'}</button>
    </div>}
  </div>;
}

function policyFields(data) {
  return {
    effort:data?.effort,
    fast:data?.fast,
    quotaEnabled:data?.quotaEnabled ?? true,
    serverQuotaEnabled:data?.serverQuotaEnabled ?? true,
    limits:Object.fromEntries(quotaFields.map(([id])=>[id,inputAmount(data?.[id])])),
    revision:data?.revision,
  };
}

export function useTerraSettings(api, revision) {
  const resource = useTerraResource(api, undefined, revision);
  const [policyDraft,setPolicyDraft] = useState(null);
  const [credentialDraft,setCredentialDraft] = useState(null);
  const base = resource.data;
  const policy = policyDraft ?? policyFields(base);
  const credential = base?.credential ?? null;
  const amounts = Object.fromEntries(quotaFields.map(([id])=>[id,quotaAmount(policy.limits[id],base?.[id])]));
  const quotaReady = Boolean(base && quotaFields.every(([id])=>Number.isSafeInteger(base[id])));
  const quotaChanged = quotaReady && (policy.quotaEnabled !== (base.quotaEnabled ?? true)
    || policy.serverQuotaEnabled !== (base.serverQuotaEnabled ?? true)
    || quotaFields.some(([id])=>amounts[id] !== base[id]));
  const policyDirty = Boolean(policyDraft && (!base || policy.effort!==base.effort || policy.fast!==base.fast || quotaChanged));
  const credentialDirty = credentialDraft !== null;
  const policyConflict = policyDirty && base && policy.revision !== base.revision;
  const credentialConflict = credentialDirty && credential && credentialDraft.revision !== credential.revision;
  const conflict = Boolean(policyConflict || credentialConflict);
  const dirty = !resource.forbidden && (policyDirty || credentialDirty);
  const invalid = quotaChanged && Object.values(amounts).some(value=>value === null);

  useEffect(() => {
    if (resource.forbidden) {
      setPolicyDraft(null);
      setCredentialDraft(null);
    } else if (policyDraft && base && policyDraft.revision !== base.revision && !policyDirty) {
      setPolicyDraft(null);
    }
  }, [resource.forbidden,base,policyDraft,policyDirty]);

  function editPolicy(fields) { setPolicyDraft({...policy,...fields}); }
  function editAmount(id, value) { editPolicy({limits:{...policy.limits,[id]:value}}); }
  function editCredential(key) {
    setCredentialDraft(key === '' ? null : {key,revision:credentialDraft?.revision ?? credential.revision});
  }
  function discard() {
    if (policyConflict) setPolicyDraft(null);
    if (credentialConflict) setCredentialDraft(null);
  }

  async function save() {
    if (credentialDirty) {
      setCredentialDraft(null);
      const key = credentialDraft.key === null ? null : credentialDraft.key.trim();
      if (!await resource.saveCredential({key,revision:credentialDraft.revision})) return false;
    }
    if (policyDirty) {
      const body = {effort:policy.effort,fast:policy.fast,revision:policy.revision};
      if (quotaChanged) Object.assign(body,amounts,{quotaEnabled:policy.quotaEnabled,serverQuotaEnabled:policy.serverQuotaEnabled});
      if (!await resource.save(body)) return false;
      setPolicyDraft(null);
    }
    return true;
  }

  return {
    resource, policy, credential, credentialDraft, editPolicy, editAmount, editCredential, quotaReady, invalid,
    dirty, conflict, discard, save,
    ready: Boolean(base && !resource.busy && !conflict && !invalid
      && (!credentialDirty || (credential && (credentialDraft.key === null || credentialDraft.key.trim())))),
    remove:credentialDraft?.key === null,
  };
}

function credentialStatus(credential, remove) {
  if (remove) return '待移除';
  if (!credential) return '—';
  if (credential.configured) return credential.source === 'manager' ? '已配置 · Manager 网页' : '已配置 · 环境配置';
  if (credential.source === 'invalid' || credential.hasManaged) return '密钥配置不可用';
  return '未配置';
}

export function TerraModelFields({settings, disabled, preview, onPreviewBillingHold, toast}) {
  const {resource,policy,credential,credentialDraft,editPolicy,editCredential,conflict,discard,remove} = settings;
  const locked = disabled || resource.busy || !resource.data || conflict;
  const canRemove = credential?.hasManaged || credential?.source === 'manager';
  const field = credential?.configured ? '替换 OpenAI 项目 API Key' : 'OpenAI 项目 API Key';
  const billingBlocked = resource.data?.billingBlocked === true;
  const holdId = resource.data?.billingHoldId;
  const canResume = billingBlocked && !disabled && !resource.busy && Boolean(credential?.revision)
    && typeof holdId === 'string' && holdId.length > 0;

  async function resume() {
    if (!canResume) return;
    const next = await resource.resume({revision:credential.revision,holdId});
    if (next?.billingBlocked === false) toast('已恢复 Terra 使用。');
  }

  return <section className="settings-section wza-terra-settings">
    <div className="settings-section-heading"><div><h2>ChatGPT 接口</h2><p>配置服务器调用 GPT-6.1 Terra 所用的 OpenAI 项目密钥。</p></div></div>
    {resource.forbidden ? <p className="muted">仅服主可读取和修改 ChatGPT 接口。</p> : <>
      {billingBlocked && <div className="inline-actions wza-terra-billing">
        <p role="status">OpenAI 项目额度暂不可用，玩家已切换到 DeepSeek</p>
        <button type="button" className="button secondary" disabled={!canResume} onClick={resume}>充值后恢复使用</button>
      </div>}
      {preview && !billingBlocked && onPreviewBillingHold && <button type="button" className="button quiet"
        disabled={disabled || resource.busy || !credential?.configured} onClick={onPreviewBillingHold}>模拟 OpenAI 项目额度耗尽</button>}
      <div className="setting-row"><div><strong>思考等级</strong><p>应用于服务器 Terra 请求。</p></div>
        <Select label="Terra 思考强度" value={policy.effort} options={effortOptions}
          onChange={effort=>editPolicy({effort})} disabled={locked}/></div>
      <div className="setting-row"><div><strong>服务速度</strong><p>高速模式按对应费率计费。</p></div>
        <Select label="Terra 服务速度" value={policy.fast === undefined ? undefined : policy.fast ? 'fast' : 'standard'}
          options={speedOptions} onChange={value=>editPolicy({fast:value==='fast'})} disabled={locked}/></div>
      <dl className="detail-definition">
        <div><dt>密钥状态</dt><dd>{credentialStatus(credential,remove)}</dd></div>
        <div><dt>最近修改</dt><dd>{formatTime(credential?.updatedAt)}</dd></div>
      </dl>
      <label className="form-group"><span>{field}</span>
        <input type="password" autoComplete="new-password" value={credentialDraft?.key ?? ''}
          disabled={locked || !credential || remove} onChange={event=>editCredential(event.target.value)}
          placeholder={preview?'输入测试密钥':'输入 OpenAI 项目 API Key'} maxLength={4096}/>
      </label>
      <p className="muted">{preview?'本地演示仅保存配置状态。请使用测试值。'
        : credential?.hasExternal ? '网页密钥优先生效，移除后由 Manager 读取环境配置。'
        : '密钥保存到 Manager 服务，保存后只显示配置状态。'}</p>
      {canRemove && <button className="button quiet" disabled={locked} onClick={()=>editCredential(remove ? '' : null)}>
        {remove?'撤销移除 OpenAI 密钥':'移除 OpenAI 密钥'}</button>}
      {!resource.data && resource.busy && <p className="muted" role="status">正在读取 ChatGPT 接口设置…</p>}
      {resource.data && !credential && <p className="muted">当前 Manager 暂未提供密钥状态。</p>}
      {conflict && <div className="inline-actions">
        <span className="muted">Terra 设置已在另一窗口更新。</span>
        <button className="button secondary" disabled={resource.busy} onClick={discard}>载入最新设置</button>
      </div>}
    </>}
    {!resource.forbidden && <ErrorNotice error={resource.error} retry={!resource.data && !resource.busy ? resource.read : undefined}/>}
  </section>;
}

export function TerraQuotaFields({settings, disabled}) {
  const {resource,policy,editPolicy,editAmount,quotaReady,conflict,invalid} = settings;
  const locked = disabled || resource.busy || !quotaReady || conflict;
  if (resource.forbidden) return null;
  return <div className="wza-terra-quota-settings">
    <Switch label="启用 GPT 个人限额" value={policy.quotaEnabled} disabled={locked}
      description="管控每位玩家的周额度和月额度。" onChange={quotaEnabled=>editPolicy({quotaEnabled})}/>
    <Switch label="启用 GPT 全服月预算" value={policy.serverQuotaEnabled} disabled={locked}
      description="覆盖所有玩家的 Terra 请求。" onChange={serverQuotaEnabled=>editPolicy({serverQuotaEnabled})}/>
    <div className="wza-quota-controls">{quotaFields.map(([id,label])=><AmountInput key={id} unit="USD"
      label={label} value={policy.limits[id]} disabled={locked} onChange={value=>editAmount(id,value)}/>)}</div>
    <p className="muted">{invalid?'请输入有效美元金额，最多三位小数。':'启用相应限额时，填 0 将暂停该范围的新请求。'}</p>
  </div>;
}
