import React, {useState} from 'react';
import {Confirm, ErrorNotice, Switch} from '@manager-console/titles/ui.jsx';
import './quota.css';

const prices = new Intl.NumberFormat('zh-CN', {minimumFractionDigits:3, maximumFractionDigits:3});
const clock = new Intl.DateTimeFormat('sv-SE', {timeZone:'Asia/Shanghai', year:'numeric', month:'2-digit', day:'2-digit', hour:'2-digit', minute:'2-digit'});
const valid = value => Number.isSafeInteger(value) && value >= 0;
const price = value => valid(value) ? '¥' + prices.format(value / 1e9) : '—';
export const inputAmount = value => valid(value) ? String(value / 1e9) : '';

export function parseAmount(text) {
  if (!/^\d+(\.\d{1,3})?$/.test(text.trim())) return null;
  const [yuan, fraction = ''] = text.trim().split('.');
  const result = Number(yuan) * 1e9 + Number(fraction.padEnd(3, '0')) * 1e6;
  return valid(result) ? result : null;
}

export function AmountInput({label, value, onChange, disabled, unit='元'}) {
  return <label className="wza-quota-amount"><span>{label}</span>
    <span className="wza-amount-value"><input inputMode="decimal" autoComplete="off" value={value} onChange={e=>onChange(e.target.value)} disabled={disabled}/><span>{unit}</span></span>
  </label>;
}

function remainingLabel(quota) {
  if (!quota) return '—';
  if (!quota.enabled) return '未启用';
  if (quota.unlimited) return '不限额';
  return price(quota.remainingNanoYuan);
}

export function QuotaCells({quota}) {
  const remaining = remainingLabel(quota);
  const metered = quota?.enabled && !quota.unlimited && valid(quota.remainingNanoYuan) && valid(quota.limitNanoYuan);
  const percent = metered && quota.limitNanoYuan ? Math.min(100, 100 * quota.remainingNanoYuan / quota.limitNanoYuan) : 0;
  const ratio = percent > 0 && percent < 1 ? '<1%' : Math.floor(percent)+'%';
  const title = metered ? '剩余 ' + remaining + ' / 周限额 ' + price(quota.limitNanoYuan) + ' · 已用 ' + price(quota.spentNanoYuan) : remaining;
  return <>
    <span>{!quota ? '—' : !quota.enabled ? '未启用' : quota.unlimited ? '不限额' : price(quota.limitNanoYuan)}</span>
    <span className="wza-quota-remaining" title={title} aria-label={title}>
      {metered ? <><i className="wza-quota-ring" style={{'--quota-fill':percent+'%'}} aria-hidden="true"/>{ratio}</> : remaining}
    </span>
    <span className="wza-quota-total">{price(quota?.lifetimeNanoYuan)}</span>
  </>;
}

export function PlayerQuota({quota, mutate, toast}) {
  const [editing, setEditing] = useState(null);
  const [confirm, setConfirm] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  if (!quota) return <section className="wza-agreement"><h3>使用限额</h3><p className="muted">暂时无法读取额度。</p></section>;
  const savedMode = quota.limitedMode ?? (quota.mode === 'custom' ? 'custom' : 'inherit');
  const savedAmount = quota.customLimitNanoYuan ?? (savedMode === 'custom' ? quota.limitNanoYuan : 2_000_000_000);
  const draft = editing ?? {unlimited:quota.unlimited, mode:savedMode, text:inputAmount(savedAmount)};
  const amount = parseAmount(draft.text);
  const invalid = draft.mode === 'custom' && amount === null;
  const dirty = draft.unlimited !== quota.unlimited || draft.mode !== savedMode || (draft.mode === 'custom' && amount !== savedAmount);
  async function save(reset) {
    setBusy(true);
    setError('');
    try {
      const body = {revision:quota.revision};
      if (!reset) {
        body.mode = draft.unlimited ? 'unlimited' : draft.mode;
        if (draft.unlimited) body.limitedMode = draft.mode;
        if (draft.mode === 'custom') body.limitNanoYuan = amount;
      }
      await mutate(reset ? 'quota.reset' : 'quota.player', body);
      setEditing(null);
      setConfirm(false);
      toast(reset ? '额度和七天周期已重置' : '玩家限额已保存');
    } catch (e) { setConfirm(false); setError(e.message); }
    finally { setBusy(false); }
  }
  return <section className="wza-agreement wza-player-quota">
    <h3>使用限额</h3>
    <Switch label="不限额" value={draft.unlimited} disabled={busy || invalid}
      onChange={unlimited=>setEditing({...draft,unlimited})}/>
    {!draft.unlimited && <>
    <div className="switch-row wza-personal-limit">
      <strong>独立额度限制</strong>
      <div className="wza-personal-limit-controls">
        {draft.mode==='custom' && <label className="wza-quota-amount"><span className="wza-amount-value">
          <input aria-label="个人周限额" inputMode="decimal" autoComplete="off" value={draft.text} disabled={busy}
            onChange={e=>setEditing({...draft,text:e.target.value})}/><span>元</span>
        </span></label>}
        <button type="button" role="switch" aria-label="独立额度限制" aria-checked={draft.mode==='custom'}
          disabled={busy} className="switch" onClick={()=>setEditing({...draft,mode:draft.mode==='custom'?'inherit':'custom'})}><span/></button>
      </div>
    </div>
    {invalid && <p className="muted">请输入有效金额，最多保留三位小数。</p>}
    </>}
    <dl className="detail-definition">
      <div><dt>本期剩余</dt><dd>{remainingLabel(quota)}</dd></div>
      <div><dt>本期已用</dt><dd>{price(quota.spentNanoYuan)}</dd></div>
      <div><dt>额度周期（北京时间）</dt><dd>{quota.periodStart && quota.resetAt ? clock.format(quota.periodStart)+' 至 '+clock.format(quota.resetAt) : '尚未开始'}</dd></div>
      <div><dt>累计 API 消耗</dt><dd>{price(quota.lifetimeNanoYuan)}</dd></div>
    </dl>
    <div className="inline-actions"><button className="button secondary" disabled={busy || !dirty || invalid} onClick={()=>save(false)}>保存限额</button>
      {dirty && <button className="button quiet" disabled={busy} onClick={()=>{setEditing(null); setError('');}}>取消修改</button>}
      <button className="button quiet" disabled={busy || dirty} onClick={()=>setConfirm(true)}>重置额度及周期</button></div>
    <ErrorNotice error={error}/>
    {confirm && <Confirm title="重置这位玩家的额度及周期？" subtitle="问舟AI" label="确认重置" confirm={()=>save(true)} cancel={()=>setConfirm(false)} busy={busy}>
      本期已用额度将清零，新周期从当前分钟起计算七天。累计 API 消耗会保留。
    </Confirm>}
  </section>;
}
