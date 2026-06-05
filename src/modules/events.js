/**
 * 极简事件总线
 * 用法：
 *   import { on, off, emit } from './events.js';
 *   on('printer:status', handler);
 *   emit('printer:status', { connection: 'connected' });
 *   off('printer:status', handler);
 */
const _bus = {};

export const on = (event, fn) => {
  (_bus[event] = _bus[event] || []).push(fn);
};

export const off = (event, fn) => {
  _bus[event] = (_bus[event] || []).filter(f => f !== fn);
};

export const emit = (event, data) => {
  (_bus[event] || []).slice().forEach(fn => {
    try { fn(data); } catch(e) { console.error(`[events] ${event} handler error`, e); }
  });
};
