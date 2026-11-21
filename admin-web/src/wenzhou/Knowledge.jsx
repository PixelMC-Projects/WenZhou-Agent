import React, {useEffect, useState} from 'react';
import {Books, MagnifyingGlass, Rows} from '@phosphor-icons/react';
import {Empty, ErrorNotice, Modal, Pagination} from '@manager-console/titles/ui.jsx';
import {SortLabel} from '@manager-console/titles/SortLabel.jsx';
import {request} from './api.js';
import {formatTime} from './data.js';

const size = 50;
export default function Knowledge({revision}) {
  const [query,setQuery] = useState('');
  const [sort,setSort] = useState({key:'created',direction:'desc'});
  const [index,setIndex] = useState(0);
  const [compact,setCompact] = useState(false);
  const [selected,setSelected] = useState(null);
  const [list,setList] = useState(null);
  const [detail,setDetail] = useState(null);
  const [error,setError] = useState('');
  const [detailError,setDetailError] = useState('');
  const [retry,setRetry] = useState(0);
  useEffect(()=>{
    const controller = new AbortController();
    setError('');
    request('knowledge.list',{query,page:index,sort:sort.key,direction:sort.direction},controller.signal)
      .then(result=>{if (!controller.signal.aborted) setList(result);})
      .catch(e=>{if (!controller.signal.aborted) {setList(null);setError(e.message);}});
    return ()=>controller.abort();
  },[revision,query,index,sort,retry]);
  useEffect(()=>{
    if (!selected) return;
    const controller = new AbortController();
    setDetailError('');
    request('knowledge.detail',{id:selected.id,scope:selected.scope},controller.signal)
      .then(result=>{if (!controller.signal.aborted) setDetail(result);})
      .catch(e=>{if (!controller.signal.aborted) {setDetail(null);setDetailError(e.message);}});
    return ()=>controller.abort();
  },[selected,revision,retry]);
  return <>
    <div className="view-bar"><div className="view-tabs"><button className="active">全部知识<small className="tab-count">{list?.count ?? '…'}</small></button></div>
      <div className="view-actions"><button className="button quiet" aria-label="切换列表密度" aria-pressed={compact} onClick={()=>setCompact(!compact)}><Rows size={14}/>{compact?'紧凑':'舒适'}</button></div></div>
    <div className="content-area" data-compact={compact}><div className="list-search-row"><MagnifyingGlass size={15}/><input aria-label="搜索知识记录" maxLength={256} placeholder="搜索名称、注册 ID 或关键词…" value={query} onChange={e=>{setQuery(e.target.value);setIndex(0);}}/></div>
      <div className="table-heading wza-grid wza-knowledge">{[['name','知识摘要'],['pack','整合包'],['created','首次保存'],['updated','最近更新']].map(([field,label])=>
        <span key={field}><SortLabel label={label} field={field} sort={sort} onChange={next=>{setSort(next);setIndex(0);}}/></span>)}</div>
      <div className="scrolling-rows">{list?.rows.map(n=><button key={n.scope + '/' + n.id} className="player-row wza-grid wza-knowledge wza-record" onClick={()=>{setDetail(null);setDetailError('');setSelected(n);}}>
        <span className="player-identity"><strong>{n.name}</strong><small title={n.subject}>{n.subject}</small></span><span>{n.pack}</span><time>{formatTime(n.created)}</time><time>{formatTime(n.updated)}</time>
      </button>)}{error && <ErrorNotice error={error} retry={()=>setRetry(n=>n+1)}/>}
        {!list && !error && <span role="status" className="inline-loading">正在读取知识记录…</span>}{list && !list.rows.length&&<Empty icon={Books} title="没有匹配的知识记录"/>}</div></div>
    <Pagination page={list?.page ?? 0} size={size} total={list?.total ?? 0} onPage={setIndex}>每页 {size} 项 · 北京时间</Pagination>
    {selected&&<Modal wide title={selected.name} subtitle="WenzhouAI · 知识记录" close={()=>setSelected(null)}>
      <div className="editor-body">{detailError ? <ErrorNotice error={detailError} retry={()=>setRetry(n=>n+1)}/> : !detail ? <span role="status" className="inline-loading">正在读取正文…</span> : <><section className="wza-turn"><h3>知识正文</h3><p>{detail.detail}</p></section>
      <dl className="detail-definition">{[['整合包',detail.pack],['主题',detail.subject],['类别',detail.facet],['首次保存',formatTime(detail.created)],['最近更新',formatTime(detail.updated)],['关键词',detail.keywords.join('、')],['证据来源',detail.sources.join('、')]].map(([label,value])=><div key={label}><dt>{label}</dt><dd>{value}</dd></div>)}</dl></>}</div>
    </Modal>}
  </>;
}
