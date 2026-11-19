const count = n => Number.isSafeInteger(n) && n >= 0;
export function summarizeUsage(turns) {
  const total = { hit: 0, miss: 0, output: 0, reasoning: 0, nanoYuan: 0, missing: false, known: false };
  const seen = new Set();
  for (const turn of turns) {
    if (!Array.isArray(turn.usageReceipts)) { total.missing = true; continue; }
    for (const receipt of turn.usageReceipts) {
      if (!receipt?.id || seen.has(receipt.id)) continue;
      seen.add(receipt.id);
      if (![receipt.hit, receipt.miss, receipt.output].every(count)) { total.missing = true; continue; }
      total.known = true;
      for (const field of ['hit', 'miss', 'output']) total[field] += receipt[field];
      for (const field of ['reasoning', 'nanoYuan']) {
        total[field] = count(total[field]) && count(receipt[field]) ? total[field] + receipt[field] : null;
      }
    }
  }
  return total;
}

const integers = new Intl.NumberFormat('zh-CN');
const percent = new Intl.NumberFormat('zh-CN', {style:'percent', maximumFractionDigits:1});
const yuan = new Intl.NumberFormat('zh-CN', { maximumFractionDigits:3, minimumFractionDigits:3 });
export function usageDisplay(turns) {
  const t = summarizeUsage(turns);
  const unavailable = t.missing && !t.known;
  const number = n => unavailable || n === null ? '—' : integers.format(n);
  const cost = unavailable || t.nanoYuan === null ? '—' : '¥' + yuan.format(t.nanoYuan / 1e9);
  return {
    cost, note: !t.missing ? '' : unavailable ? '用量未记录' : '仅含已记录用量',
    metrics: [
      ['未命中输入', number(t.miss)], ['命中输入', number(t.hit)], ['输出', number(t.output)],
      ['其中思考', number(t.reasoning)], ['缓存命中率', t.hit + t.miss ? percent.format(t.hit / (t.hit + t.miss)) : '—'],
      ['累计费用', cost],
    ],
  };
}

