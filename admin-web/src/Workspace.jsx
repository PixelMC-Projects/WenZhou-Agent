import React, {useCallback, useEffect, useState} from 'react';
import {ErrorNotice} from '@manager-console/titles/ui.jsx';
import {request} from './wenzhou/api.js';
import Records from './wenzhou/Records.jsx';
import Service from './wenzhou/Service.jsx';
import './wenzhou/workspace.css';

export default function Workspace({page, theme, toast, setUnsaved, gameConnected}) {
  return <div className="titles-module wza-module" data-theme={theme}>
    {page === 'wenzhou-service'
      ? <ServiceSettings toast={toast} setUnsaved={setUnsaved} gameConnected={gameConnected}/>
      : <Records key={page} people={page === 'wenzhou-players'} toast={toast} gameConnected={gameConnected}/>}
  </div>;
}

function ServiceSettings({toast, setUnsaved, gameConnected}) {
  const [tab,setTab] = useState('key');
  const [status, setStatus] = useState(null);
  const [error, setError] = useState('');
  const [revision, setRevision] = useState(0);
  const update = useCallback(next => setStatus(current => ({...current, ...next})), []);

  useEffect(() => {
    const controller = new AbortController();
    setError('');
    request('status', {}, controller.signal)
      .then(next => {
        if (!controller.signal.aborted) update(next);
      })
      .catch(failure => {
        if (!controller.signal.aborted) setError(failure.message);
      });
    return () => controller.abort();
  }, [revision, update]);

  return <>
    <div className="view-bar"><div className="view-tabs" role="tablist" aria-label="服务设置分类">{[['key','模型服务'],['terms','用户协议']].map(([id,label])=>
      <button key={id} role="tab" aria-selected={tab===id} className={tab===id?'active':''} onClick={()=>setTab(id)}>{label}</button>)}</div></div>
    {!gameConnected && status && <p className="quiet-note" role="status">游戏服未连接。使用限额仍可修改。密钥、思考等级与协议设置在游戏服连接后可修改。</p>}
    <ErrorNotice error={error} retry={() => setRevision(value => value + 1)}/>
    {status
      ? <Service tab={tab} {...status} update={update} toast={toast} setUnsaved={setUnsaved} gameConnected={gameConnected}/>
      : !error && <span className="inline-loading" role="status">正在读取服务设置…</span>}
  </>;
}
