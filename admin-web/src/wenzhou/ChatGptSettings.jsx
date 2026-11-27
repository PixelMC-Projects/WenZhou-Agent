import React, {useEffect, useState} from 'react';
import {Confirm, ErrorNotice} from '@manager-console/titles/ui.jsx';
import {request} from './api.js';

export default function ChatGptSettings({online, revision, toast}) {
  const [account, setAccount] = useState(null);
  const [error, setError] = useState('');
  const [readError, setReadError] = useState('');
  const [busy, setBusy] = useState(false);
  const [confirm, setConfirm] = useState(false);
  const [authorizationUrl, setAuthorizationUrl] = useState('');
  const [reload, setReload] = useState(0);
  const pending = account?.status === 'awaiting' || account?.status === 'verifying';

  useEffect(() => {
    if (!online || busy) return;
    let attempt;
    let timer;
    const pollUntil = Date.now() + 360000;
    async function read() {
      if (document.hidden) return;
      clearTimeout(timer);
      attempt?.abort();
      const current = new AbortController();
      attempt = current;
      try {
        const status = await request('chatgpt.status', {}, current.signal);
        if (!current.signal.aborted) {
          setAccount(status);
          setReadError('');
          if (status.status !== 'awaiting') setAuthorizationUrl('');
        }
      } catch (failure) {
        if (!current.signal.aborted) setReadError(failure.message);
      } finally {
        if (!current.signal.aborted && pending && Date.now() < pollUntil) timer = setTimeout(read, 1500);
      }
    }
    read();
    document.addEventListener('visibilitychange', read);
    window.addEventListener('focus', read);
    return () => {
      attempt?.abort();
      clearTimeout(timer);
      document.removeEventListener('visibilitychange', read);
      window.removeEventListener('focus', read);
    };
  }, [online, pending, busy, reload, revision]);

  async function act(operation) {
    setBusy(true);
    setError('');
    try {
      const next = await request('chatgpt.' + operation);
      setAccount(next);
      if (operation === 'login' && next.authorizationUrl) {
        const url = new URL(next.authorizationUrl);
        if (url.origin !== 'https://auth.openai.com' || url.pathname !== '/api/accounts/authorize') {
          setError('ChatGPT 授权地址无效');
        } else {
          setAuthorizationUrl(url.href);
        }
      } else if (operation === 'logout' && next.remoteRevoked) {
        toast('已断开 ChatGPT 并撤销授权');
      } else if (operation === 'models' && !next.message) {
        toast('ChatGPT 模型列表已更新');
      }
    } catch (failure) {
      setError(failure.message);
    } finally {
      setBusy(false);
      setConfirm(false);
    }
  }

  const status = !online ? '游戏服未连接' : !account ? '正在读取授权状态…'
    : pending || !account.connected ? {
      awaiting: '等待授权', verifying: '正在验证', plan_denied: '已登录，未授予方案额度权限',
      signed_out: '已断开', error: '登录未完成', storage_error: '凭据未能清除', disabled: '未启用', idle: '未登录',
    }[account.status] : '已授权使用 ChatGPT 方案';

  return <section className="settings-section" aria-labelledby="wza-chatgpt-title">
    <div className="settings-section-heading"><h2 id="wza-chatgpt-title">ChatGPT 账号</h2></div>
    <ErrorNotice error={online ? error || readError : ''} retry={() => { setError(''); setReload(value => value + 1); }}/>
    <div className="setting-row"><div>
      <strong role="status">{status}{online && account?.email ? ` · ${account.email}` : ''}</strong>
      <p>{online && account?.message ? account.message : '登录后，只有绑定的服主可以在问舟中选择 GPT 模型。'}</p>
      {online && account?.enabled && !account.connected && <p>从远程电脑管理时，请先通过 SSH 转发回环端口 {account.callbackPort}。</p>}
    </div>
    {online && account?.enabled && (authorizationUrl
      ? <a className="button primary" href={authorizationUrl} target="_blank" rel="noopener noreferrer" data-oai-siwc-button="">Continue with ChatGPT</a>
      : (account.connected || account.status === 'storage_error') && !pending
        ? <button className="button secondary" disabled={busy} onClick={() => setConfirm(true)}>断开账号</button>
        : <button className="button primary" disabled={busy || pending} onClick={() => act('login')}>
            {account.status === 'plan_denied' ? '授权使用 ChatGPT 方案' : '使用 ChatGPT 登录'}
          </button>)}
    </div>
    {online && pending && <button className="button secondary" disabled={busy} onClick={() => act('cancel')}>取消登录</button>}
    {online && account?.status === 'plan_denied' &&
      <button className="button secondary" disabled={busy} onClick={() => setConfirm(true)}>断开账号</button>}
    {online && account?.connected && <button className="button secondary" disabled={busy || pending} onClick={() => act('models')}>刷新模型</button>}
    {online && account?.connected && account.models?.length > 0 && <details>
      <summary>当前账号可用模型</summary>
      <ul>{account.models.map(model => <li key={model.id}>{model.label}</li>)}</ul>
    </details>}
    {online && account?.connected && <p>
      <a href="https://chatgpt.com/settings/usage" target="_blank" rel="noopener noreferrer">查看 ChatGPT 用量</a>
    </p>}
    {confirm && <Confirm title="断开 ChatGPT 账号？" subtitle="问舟 AI" label="断开账号"
      confirm={() => act('logout')} cancel={() => setConfirm(false)} busy={busy || !online}>
      断开将停用问舟的 ChatGPT 方案授权，并请求 OpenAI 撤销当前会话。
    </Confirm>}
  </section>;
}
