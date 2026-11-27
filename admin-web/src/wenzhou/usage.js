const count = n => Number.isSafeInteger(n) && n >= 0;
export function summarizeUsage(turns) {
  const total = {
    hit: 0, miss: 0, output: 0, reasoning: 0, nanoYuan: 0, nanoUsd: 0,
    planReports: 0, deepSeekReports: 0, openAiReports: 0, missing: false, known: false,
  };
  const seen = new Set();
  for (const turn of turns) {
    if (turn.usageMissing === true) total.missing = true;
    if (!Array.isArray(turn.usageReceipts)) { total.missing = true; continue; }
    for (const receipt of turn.usageReceipts) {
      if (!receipt?.id || seen.has(receipt.id)) continue;
      seen.add(receipt.id);
      if (![receipt.hit, receipt.miss, receipt.output].every(count)) { total.missing = true; continue; }
      total.known = true;
      for (const field of ['hit', 'miss', 'output']) total[field] += receipt[field];
      total.reasoning = count(total.reasoning) && count(receipt.reasoning)
        ? total.reasoning + receipt.reasoning : null;
      total.missing ||= total.reasoning === null;
      if (receipt.billing === 'chatgpt_plan') {
        total.planReports++;
      } else if (receipt.billing === 'openai_api') {
        total.openAiReports++;
        total.nanoUsd = count(total.nanoUsd) && count(receipt.nanoUsd)
          ? total.nanoUsd + receipt.nanoUsd : null;
        total.missing ||= total.nanoUsd === null;
      } else {
        total.deepSeekReports++;
        total.nanoYuan = count(total.nanoYuan) && count(receipt.nanoYuan)
          ? total.nanoYuan + receipt.nanoYuan : null;
        total.missing ||= total.nanoYuan === null;
      }
    }
  }
  return total;
}

const integers = new Intl.NumberFormat('zh-CN');
const percent = new Intl.NumberFormat('zh-CN', {style:'percent', maximumFractionDigits:1});
const yuan = new Intl.NumberFormat('zh-CN', { maximumFractionDigits:3, minimumFractionDigits:3 });
const dollars = new Intl.NumberFormat('en-US', {style:'currency', currency:'USD', minimumFractionDigits:3, maximumFractionDigits:6});
export function usageDisplay(turns) {
  const t = summarizeUsage(turns);
  const unavailable = t.missing && !t.known;
  const number = n => unavailable || n === null ? '—' : integers.format(n);
  const hasApi = t.deepSeekReports > 0 || t.openAiReports > 0;
  const planOnly = t.planReports > 0 && !hasApi;
  const yuanCost = unavailable || t.nanoYuan === null ? '—' : '¥' + yuan.format(t.nanoYuan / 1e9);
  const usdCost = t.nanoUsd === null ? '—' : t.nanoUsd > 0 && t.nanoUsd < 1000 ? '<$0.000001' : dollars.format(t.nanoUsd / 1e9);
  const costMetrics = [];
  if (planOnly) costMetrics.push(['计费来源', 'ChatGPT 套餐']);
  if (t.deepSeekReports > 0 || (!t.openAiReports && !planOnly)) {
    costMetrics.push([t.openAiReports || t.planReports ? 'DeepSeek 累计费用' : '累计费用', yuanCost]);
  }
  if (t.openAiReports) costMetrics.push(['Terra 累计费用', usdCost]);
  if (hasApi && t.planReports) costMetrics.push(['ChatGPT 套餐请求', integers.format(t.planReports)]);
  return {
    cost: costMetrics[0][1], note: !t.missing ? '' : unavailable ? '用量未记录' : '仅含已记录用量',
    metrics: [
      ['未命中输入', number(t.miss)], ['命中输入', number(t.hit)], ['输出', number(t.output)],
      ['其中思考', number(t.reasoning)], ['缓存命中率', t.hit + t.miss ? percent.format(t.hit / (t.hit + t.miss)) : '—'],
      ...costMetrics,
    ],
  };
}

