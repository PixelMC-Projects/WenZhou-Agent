import React, {useCallback, useEffect, useLayoutEffect, useRef, useState} from 'react';
import {ArrowLeft, ArrowSquareOut, ChatCircleText, MagnifyingGlass, Rows, Users} from '@phosphor-icons/react';
import {Empty, ErrorNotice, Modal, Pagination, Switch, useDebounced} from '@manager-console/titles/ui.jsx';
import {QuotaCells, PlayerQuota} from './Quota.jsx';
import {SortLabel} from '@manager-console/titles/SortLabel.jsx';
import {usageDisplay} from './usage.js';
import {request} from './api.js';
import {allowsApi, answerLines, formatTime, receiptStatus} from './data.js';

const playersPerPage = 50;
const sessionsPerPage = 20;

function Tabs({items,value,select,label}) {
  return <div className="view-tabs" role="tablist" aria-label={label}>{items.map(([id,name,count])=>
    <button key={id} role="tab" aria-selected={value===id} className={value===id?'active':''} onClick={()=>select(id)}>
      {name}{count!==undefined&&<small className="tab-count">{count ?? '—'}</small>}
    </button>
  )}</div>;
}

function Identity({player}) {
  const [missing,setMissing] = useState(false);
  return <>{missing ? <span className="player-avatar wza-avatar-fallback" aria-hidden="true"><Users size={17}/></span>
    : <img className="player-avatar" src={'/api/avatars/'+player.playerId+'.png'} alt="" width={32} height={32}
      onError={()=>setMissing(true)}/>}
    <span className="player-identity"><strong><span className={'status-dot '+(player.isOnline?'online':'')} role="img" aria-label={player.isOnline?'在线':'离线'}/>{player.name}</strong>
      <small className="mono player-uuid" title={player.playerId}>{player.playerId}</small></span></>;
}

function PlayerDetail({player,policy,showAgreement,hidden,onOpen,onChange,tab,setTab,toast,gameConnected}) {
  const [history,setHistory] = useState(null);
  const [page,setPage] = useState(0);
  const [error,setError] = useState('');
  const [busy,setBusy] = useState(false);
  useEffect(()=>{
    if (tab !== 'history') return;
    const controller = new AbortController();
    setError('');
    request('sessions', {playerId:player.playerId, page, sort:{key:'updated',direction:'desc'}}, controller.signal, gameConnected)
      .then(result=>{if (!controller.signal.aborted) setHistory(result);})
      .catch(e=>{if (!controller.signal.aborted) setError(e.message);});
    return () => controller.abort();
  },[tab,player.playerId,page,gameConnected]);

  async function changeGrant(allowed) {
    setBusy(true);
    setError('');
    try {
      const result = await request('grant', {playerId:player.playerId, allowed, revision:player.grantRevision});
      onChange({...player,...result});
      toast(allowed?'已授予问舟访问权限':'已撤销问舟访问权限');
    } catch (e) { setError(e.message); }
    finally { setBusy(false); }
  }

  const receipt = player.receipt;
  const status = receiptStatus(receipt,policy);
  return <>
    <div className="view-bar" hidden={hidden}><Tabs label="玩家详情" value={tab} select={setTab}
      items={[[ 'access',showAgreement?'权限与协议':'访问权限'],['history','历史会话',player.count]]}/></div>
    <div className="editor-body" hidden={hidden}>
      <ErrorNotice error={error}/>
      {tab==='access'?<>
        <div className="wza-player player-link"><Identity player={player}/></div>
        <Switch label="允许访问问舟" value={player.granted} disabled={busy} onChange={changeGrant}
          description="授权后，玩家手册左侧将出现问舟入口。"/>
        <PlayerQuota quota={player.quota} toast={toast} mutate={async (operation,body) => {
          const result = await request(operation, {...body,playerId:player.playerId});
          onChange({...player,quota:result.quota});
        }}/>
        {showAgreement && <section className="wza-agreement"><h3>用户协议</h3><dl className="detail-definition">
          <div><dt>当前状态</dt><dd>{status}</dd></div>
          <div><dt>生效版本</dt><dd>{policy.versionLabel || policy.version}</dd></div>
          <div><dt>玩家同意版本</dt><dd>{status==='已同意' ? policy.versionLabel || policy.version : receipt?.version || '—'}</dd></div>
          <div><dt>同意时间</dt><dd>{formatTime(receipt?.acceptedAt)}</dd></div>
          <div><dt>发送至 DeepSeek</dt><dd>{allowsApi(receipt)?'已同意':'未同意'}</dd></div>
          <div><dt>问答优化授权</dt><dd>{receipt?.research?(status==='已同意'?'已授权':'待重新确认'):'未授权'}</dd></div>
        </dl></section>}
      </>: <>
        {!history && !error && <span role="status">正在加载历史会话…</span>}
        {history?.rows.map(row=><button key={row.id} className="wza-history" onClick={event=>onOpen(row,event.currentTarget)}>
          <ChatCircleText size={18}/><span><strong>{row.title}</strong><small>{formatTime(row.updated)} · {row.count} 轮问答</small></span><ArrowSquareOut size={14}/>
        </button>)}
        {history?.total===0 && <Empty title="暂无历史会话" icon={ChatCircleText}/>}
        {history?.total>sessionsPerPage && <Pagination page={page} size={sessionsPerPage} total={history.total} onPage={setPage}/>}
      </>}
    </div>
  </>;
}

function Thread({row, bodyRef, onUnavailable, gameConnected}) {
  const [usage,setUsage] = useState(() => ({
    receipts:row.usageReceipts,
    missing:row.usageMissing,
    running:row.running,
  }));
  const [stale,setStale] = useState(false);

  useEffect(()=>{
    if (!gameConnected && !row.studyOnly && !row.running) return;
    let stopped = false;
    let pending;
    let timer;

    async function refresh() {
      if (stopped || document.hidden || pending) return;
      const attempt = new AbortController();
      pending = attempt;
      try {
        const latest = await request('thread.usage',
          {id:row.id,playerId:row.playerId,research:row.studyOnly},attempt.signal,gameConnected);
        if (pending === attempt) {
          setUsage(latest);
          setStale(false);
        }
      } catch (e) {
        if (pending !== attempt) return;
        if ([400,401,403,404].includes(e.status)) {
          stopped = true;
          onUnavailable(e);
        } else if (e.name !== 'AbortError') {
          setStale(true);
        }
      } finally {
        if (pending === attempt) {
          pending = undefined;
          if (!stopped && !document.hidden && (gameConnected || row.studyOnly)) {
            timer = setTimeout(refresh, 2000);
          }
        }
      }
    }

    function pause() {
      clearTimeout(timer);
      pending?.abort();
      pending = undefined;
    }

    function visibility() {
      if (document.hidden) pause();
      else refresh();
    }

    document.addEventListener('visibilitychange',visibility);
    timer = setTimeout(refresh,2000);
    return ()=>{
      stopped = true;
      pause();
      document.removeEventListener('visibilitychange',visibility);
    };
  },[row.id,row.playerId,row.studyOnly,row.running,onUnavailable,gameConnected]);

  const usageTurns = Array.isArray(usage.receipts) ? [{usageReceipts:usage.receipts}] : row.turns;
  const {metrics,note} = usageDisplay(usage.missing ? [...usageTurns,{}] : usageTurns);
  return <div className="editor-body" ref={bodyRef} tabIndex={-1} aria-label="会话正文">
    <div className="wza-thread-meta"><span>{row.name}</span><span>{row.count} 轮问答</span><time>{formatTime(row.updated)}</time></div>
    <div className="wza-thread-meta wza-thread-usage" aria-label="本对话累计用量">
      {metrics.map(([label,value])=><span key={label}>{label} {value}</span>)}{note&&<span>{note}</span>}{gameConnected&&usage.running&&<span>生成中</span>}{stale&&<span>实时用量暂未更新</span>}
    </div>
    <ThreadTurns row={row}/>
  </div>;
}

function LookupEvents({events}) {
  const [open,setOpen] = useState(false);
  return <details onToggle={event => setOpen(event.currentTarget.open)}>
    <summary>查阅过程 · {events.length}</summary>
    {open && <ul>{events.map((event,i) => <li key={i}>{event.text} {event.detail}</li>)}</ul>}
  </details>;
}

function AnswerText({text}) {
  return String(text).split('\n').map((line, index) => {
    const heading = /^#{1,6}\s+/.test(line);
    const content = line.replace(/^#{1,6}\s+/, '').replace(/^(\s*)[-*+]\s+/, '$1• ');
    const parts = content.split(/(\*\*[^*\n]+\*\*|__[^_\n]+__|`[^`\n]+`)/g);
    const children = parts.map((part, i) => {
      if (i % 2 === 0) return part;
      if (part.startsWith('`')) return <code key={i}>{part.slice(1,-1)}</code>;
      return <strong key={i}>{part.slice(2,-2)}</strong>;
    });
    return <React.Fragment key={index}>{index > 0 && <br/>}{heading ? <strong>{children}</strong> : children}</React.Fragment>;
  });
}

const ThreadTurns = React.memo(function ThreadTurns({row}) {
  return <>{row.turns.map((turn,i)=><section className="wza-turn" key={turn.id || i}>
      <h3>{row.name}<time>{formatTime(turn.createdAt)}</time></h3><p>{turn.question}</p>
      <h3>问舟</h3>{answerLines(turn).map((line,j)=><p key={j}><AnswerText text={line}/></p>)}
      {turn.events?.length>0 && <LookupEvents events={turn.events}/>}
    </section>)}
  </>;
});

export default function Records({people,toast,gameConnected}) {
  const size = people ? playersPerPage : sessionsPerPage;
  const [tab,setTab] = useState('all');
  const [compact,setCompact] = useState(false);
  const [search,setSearch] = useState('');
  const query = useDebounced(search.trim());
  const [page,setPage] = useState(0);
  const [sort,setSort] = useState(people ? {key:'grant',direction:'asc'} : {key:'updated',direction:'desc'});
  const [player,setPlayer] = useState(null);
  const [playerTab,setPlayerTab] = useState('access');
  const [selected,setSelected] = useState(null);
  const [result,setResult] = useState(null);
  const [error,setError] = useState('');
  const [refresh,setRefresh] = useState(0);
  const historyButton = useRef(null);
  const threadBody = useRef(null);
  const detailRequest = useRef(null);
  useEffect(() => () => detailRequest.current?.abort(), []);
  useLayoutEffect(()=>{
    if (selected) threadBody.current?.focus({preventScroll:true});
    else if (historyButton.current) {
      historyButton.current.focus({preventScroll:true});
      historyButton.current = null;
    }
  },[selected]);
  useEffect(()=>{
    if (search.trim() !== query) return;
    const controller = new AbortController();
    const {signal} = controller;
    let busy = false;
    let timer;
    let presenceKey;
    setError('');

    async function load() {
      clearTimeout(timer);
      if (document.hidden || signal.aborted || busy) return;
      busy = true;
      try {
        if (people && presenceKey !== undefined) {
          const {players} = await request('presence', {}, signal);
          if (signal.aborted) return;
          if (JSON.stringify(players.sort()) === presenceKey) {
            setError('');
            return;
          }
        }
        const next = await request(people ? 'players' : 'sessions', {tab,query,page,sort}, signal, gameConnected);
        if (signal.aborted) return;
        const last = Math.max(0, Math.ceil(next.total / size) - 1);
        if (page > last) setPage(last);
        else setResult({...next,tab,page});
        if (Array.isArray(next.onlineIds)) presenceKey = JSON.stringify(next.onlineIds.sort());
        setError('');
      } catch (e) {
        if (!signal.aborted) {
          if ([401,403].includes(e.status)) {
            setResult(null);
            setPlayer(null);
            setSelected(null);
            controller.abort();
          }
          setError(e.message);
        }
      } finally {
        busy = false;
        if (!document.hidden && !signal.aborted) timer = setTimeout(load, people ? 5000 : 10000);
      }
    }
    document.addEventListener('visibilitychange', load);
    load();
    return () => {
      controller.abort();
      clearTimeout(timer);
      document.removeEventListener('visibilitychange', load);
    };
  },[people,tab,query,search,page,sort,refresh,size,gameConnected]);

  const visible = result?.tab === tab && result.page === page ? result : null;
  const showAgreement = result?.showAgreement === true;
  const counts = result?.counts || {};
  const tabs = people ? [['all','全部玩家',counts.all??null],['online','在线玩家',counts.online??null],['offline','离线玩家',counts.offline??null]]
    : [['all','全部会话',counts.all??null],['research','已授权问答优化',counts.research??null]];
  const columns = people ? [['name','玩家'],['grant','访问权限'],...(showAgreement ? [['agreement','用户协议']] : []),['count','会话数'],
      ['quotaLimit','每周限额'],['quotaRemaining','剩余额度'],['quotaLifetime','累计 API 消耗']]
    : [['title','会话'],['name','玩家'],['count','问答轮数'],['updated','最近消息']];

  async function openDetail(operation, input, row, select) {
    detailRequest.current?.abort();
    const controller = new AbortController();
    detailRequest.current = controller;
    select({...row, loading:true});
    try {
      const detail = await request(operation, input, controller.signal, gameConnected);
      if (!controller.signal.aborted) select({...detail, studyOnly:input.research});
    } catch (e) {
      if (!controller.signal.aborted) {
        select(null);
        toast(e.message);
      }
    }
  }

  function showThread(row, trigger) {
    historyButton.current = trigger;
    openDetail('thread', {id:row.id, playerId:row.playerId, research:!people && tab === 'research'}, row, setSelected);
  }

  function closeDetail() {
    detailRequest.current?.abort();
    if (selected) setSelected(null);
    else setPlayer(null);
  }

  const closeUnavailable = useCallback(error => {
    setSelected(null);
    if (error.status === 401 || error.status === 403) {
      setPlayer(null);
      setResult(null);
      setError(error.message);
    }
    toast(error.message);
  },[toast]);

  function showPlayer(row) {
    setPlayerTab('access');
    openDetail('player', {playerId:row.playerId}, row, setPlayer);
  }

  return <>
    <div className="view-bar"><Tabs label={people?'玩家分类':'会话筛选'} items={tabs} value={tab}
      select={id=>{if (tab !== id) {setTab(id);setPage(0);}}}/>
      <div className="view-actions"><button className="button quiet" aria-label="切换列表密度" aria-pressed={compact} onClick={()=>setCompact(!compact)}>
        <Rows size={14}/>{compact?'紧凑':'舒适'}
      </button></div>
    </div>
    <div className="content-area" data-compact={compact} data-agreement={showAgreement?'shown':'hidden'}>
      <div className="list-search-row"><MagnifyingGlass size={15}/><input aria-label={people?'搜索玩家':'搜索会话'}
        value={search} placeholder={people?'搜索玩家名或 UUID…':'搜索会话标题或玩家…'} onChange={e=>{setSearch(e.target.value);setPage(0);}}/></div>
      <div className={'table-heading wza-grid '+(people?'wza-players':'wza-chats')}>{columns.map(([field,label])=>
        <span key={field}><SortLabel label={label} field={field} sort={sort} onChange={next=>{setSort(next);setPage(0);}}/></span>)}</div>
      <ErrorNotice error={error} retry={()=>setRefresh(n=>n+1)}/>
      <div className="scrolling-rows">
        {!visible && !error && <span role="status" className="inline-loading">正在加载…</span>}
        {visible?.rows.map(row=><button type="button" key={people?row.playerId:row.playerId+'/'+row.id}
          className={'player-row wza-grid wza-record '+(people?'wza-players':'wza-chats')} onClick={event=>people?showPlayer(row):showThread(row,event.currentTarget)}>
          {people?<><span className="player-link"><Identity player={row}/></span><span className={row.granted?'wza-granted':'muted'}>
            {row.granted?'已授权':'未授权'}</span>{showAgreement&&<span>{row.agreement}</span>}<span>{row.count}</span><QuotaCells quota={row.quota}/></>
            :<><strong className="wza-subject">{row.title}</strong><span>{row.name}</span><span>{row.count}</span><time>{formatTime(row.updated)}</time></>}
        </button>)}
        {visible?.total===0&&<Empty icon={people?Users:ChatCircleText} title={query?'没有匹配的结果':tab==='research'?'暂无可用于优化的会话':'暂无记录'}/>}
      </div>
    </div>
    {result?.tab === tab && <Pagination page={page} size={size} total={result.total} onPage={setPage}>{people ? '每页 50 名玩家' : '每页 20 条会话'} · 北京时间</Pagination>}
    {(player || selected) && <Modal wide title={selected?.title || player?.name}
      subtitle={selected ? 'WenzhouAI · 聊天记录' : 'WenzhouAI · 玩家权限'}
      close={closeDetail}
      footer={player && selected && <button className="button secondary" onClick={closeDetail}><ArrowLeft size={14}/>返回玩家历史</button>}>
      {(selected?.loading || player?.loading) && <div className="editor-body" role="status">正在读取详情…</div>}
      {player && !player.loading && <PlayerDetail key={player.playerId}
        player={Array.isArray(visible?.onlineIds) ? {...player,isOnline:visible.onlineIds.includes(player.playerId)} : player}
        policy={player.policy} showAgreement={player.showAgreement}
        hidden={Boolean(selected)} tab={playerTab} setTab={setPlayerTab} toast={toast} onOpen={showThread} gameConnected={gameConnected}
        onChange={next=>{setPlayer(next);setRefresh(n=>n+1);}}/>}
      {selected && !selected.loading && <Thread key={selected.playerId+"/"+selected.id} row={selected} bodyRef={threadBody} onUnavailable={closeUnavailable} gameConnected={gameConnected}/>}
    </Modal>}
  </>;
}



