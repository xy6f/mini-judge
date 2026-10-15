/** UTF-8 安全的 base64（btoa 只吃 latin1，先 encodeURIComponent 过一遍） */
export function b64(text) {
  return btoa(unescape(encodeURIComponent(text)));
}

export function fmtTime(iso) {
  if (!iso) return '—';
  return String(iso).replace('T', ' ').slice(0, 19);
}
