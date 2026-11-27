const timeFormat = new Intl.DateTimeFormat('sv-SE',{
  timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit'
});
export const formatTime = ms => Number.isFinite(ms) && ms > 0 ? timeFormat.format(ms) : '—';

function itemText(parts,items) {
  if (typeof parts === 'string') return parts;
  return (Array.isArray(parts) ? parts : []).map(p=>typeof p==='string'?p:(items?.[p.item]?.name||p.item||'')+(p.explanation?'（'+p.explanation+'）':'')).join('');
}

export function answerLines(turn) {
  const reply = turn.reply;
  if (!reply) {
    const status = turn.stopped ? '已停止回答' : turn.failure;
    return status || turn.topicPrompt ? [status, turn.topicPrompt].filter(Boolean) : ['本次提问尚未收到回答。'];
  }
  const lines=[itemText(reply.text,reply.items)];
  const cards=Array.isArray(reply.recipes)&&reply.recipes.length?reply.recipes:reply.recipe?[reply.recipe]:[];
  for (const recipe of cards) {
    if (!Array.isArray(recipe?.slots) || !(recipe.columns > 0)) continue;
    const slots=recipe.slots.map(id=>id?reply.items?.[id]?.name||id:'空');
    for (let i=0;i<slots.length;i+=recipe.columns) lines.push(slots.slice(i,i+recipe.columns).join(' · '));
    lines.push('产物：'+(reply.items?.[recipe.output]?.name||recipe.output)+' × '+recipe.count);
  }
  if (reply.note) lines.push(itemText(reply.note,reply.items));
  return lines.filter(Boolean);
}

