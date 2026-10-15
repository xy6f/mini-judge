/* 当前提交轮询态 store：QUEUED → RUNNING → DONE 状态机的数据源
 * 轮询代数（generation）防竞态：新提交/页面离开会作废旧轮询的回调。 */
import { defineStore } from 'pinia';
import { http } from '../api/http';

const POLL_SUBMISSION_MS = 900;    // 提交结果轮询间隔
const POLL_MAX_WAIT_MS = 240000;   // 单条提交轮询上限（死循环 TLE 用例 + 编译也够用）

export const useSubmissionsStore = defineStore('submissions', {
  state: () => ({
    submissionId: null,
    status: '',          // QUEUED | RUNNING | REQUEUE | DONE
    resultCode: '',      // AC | WA | TLE | CE | RE | ''
    detail: null,        // DONE 后的完整返回（passed/total、耗时、错误信息）
    polling: false,
    pollError: '',       // 轮询超时/失败原因（视图 watch 后弹提示并清空）
    pollGeneration: 0,   // 取消旧轮询的令牌
    _timer: null,
    _t0: 0,
  }),
  actions: {
    /** 提交成功后开始轮询 GET /api/submissions/{id} */
    startPolling(submissionId) {
      const gen = ++this.pollGeneration;   // 作废上一轮轮询
      clearInterval(this._timer);
      this.submissionId = submissionId;
      this.status = 'QUEUED';
      this.resultCode = '';
      this.detail = null;
      this.pollError = '';
      this.polling = true;
      this._t0 = Date.now();

      const tick = async () => {
        if (gen !== this.pollGeneration) { clearInterval(this._timer); return; }
        if (Date.now() - this._t0 > POLL_MAX_WAIT_MS) {
          this.pollError = '轮询超时：评测仍未结束，可在历史中回看';
          this.stopPolling();
          return;
        }
        try {
          const d = await http.get(`/api/submissions/${submissionId}`);
          if (gen !== this.pollGeneration) return;
          this.status = d.status;
          this.resultCode = d.resultCode || '';
          if (d.status === 'DONE') {
            this.detail = d;
            this.stopPolling();
          }
        } catch (e) {
          if (gen !== this.pollGeneration) return;
          this.pollError = '结果轮询失败：' + e.message;
          this.stopPolling();
        }
      };
      tick();
      this._timer = setInterval(tick, POLL_SUBMISSION_MS);
    },
    stopPolling() {
      this.pollGeneration++;   // 使在途回调失效
      clearInterval(this._timer);
      this._timer = null;
      this.polling = false;
    },
    reset() {
      this.stopPolling();
      this.submissionId = null;
      this.status = '';
      this.resultCode = '';
      this.detail = null;
      this.pollError = '';
    },
  },
});
