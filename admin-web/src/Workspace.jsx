import React, {useCallback, useEffect, useRef, useState} from 'react';
import {ErrorNotice} from '@manager-console/titles/ui.jsx';
import {request} from './wenzhou/api.js';
import Records from './wenzhou/Records.jsx';
import Knowledge from './wenzhou/Knowledge.jsx';
import Service from './wenzhou/Service.jsx';
import './wenzhou/workspace.css';

export default function Workspace({page, theme, toast, setUnsaved, gameConnected, historyRevision, playersRevision, serviceRevision, presenceRevision, knowledgeRevision}) {
  return <div className="titles-module wza-module" data-theme={theme}>
    {page === 'wenzhou-service'
      ? <ServiceSettings serviceRevision={serviceRevision} toast={toast} setUnsaved={setUnsaved} gameConnected={gameConnected}/>
      : page === 'wenzhou-knowledge' ? <Knowledge revision={knowledgeRevision}/>
      : <Records key={page} recordsRevision={historyRevision === undefined ? undefined : historyRevision + '/' + playersRevision}
          presenceRevision={presenceRevision} people={page === 'wenzhou-players'} toast={toast} gameConnected={gameConnected}/>}
  </div>;
}

function ServiceSettings({toast, setUnsaved, gameConnected, serviceRevision}) {
  const [tab,setTab] = useState('key');
  const [status, setStatus] = useState(null);
  const [error, setError] = useState('');
  const [revision, setRevision] = useState(0);
  const mutations = useRef(0);
  const update = useCallback(next => {
    mutations.current++;
    setStatus(current => ({...current, ...next}));
  }, []);

  useEffect(() => {
    let pending;
    function read() {
      pending?.abort();
      if (document.hidden) return;
      const attempt = new AbortController();
      pending = attempt;
      const generation = mutations.current;
      request('status', {}, attempt.signal)
        .then(next => {
          if (attempt.signal.aborted || generation !== mutations.current) return;
          setStatus(next);
          setError('');
        })
        .catch(failure => {
          if (!attempt.signal.aborted) {
            if ([401, 403].includes(failure.status)) setStatus(null);
            setError(failure.message);
          }
        });
    }
    read();
    document.addEventListener('visibilitychange', read);
    // Servers predating revisioned snapshots still need to reconcile other tabs.
    const timer = serviceRevision === undefined ? setInterval(read, 10000) : undefined;
    return () => {
      pending?.abort();
      clearInterval(timer);
      document.removeEventListener('visibilitychange', read);
    };
  }, [revision, gameConnected, serviceRevision]);

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
