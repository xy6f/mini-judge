/* 题单模块级缓存：题库页与提交页（题目下拉/历史标题）共享同一份数据 */
import { ref } from 'vue';
import { http } from '../api/http';

const problems = ref([]);
const filter = ref('');
const loading = ref(false);

export function useProblems() {
  async function loadProblems(token) {
    if (!token) { problems.value = []; return; }
    loading.value = true;
    try {
      const q = filter.value ? `?difficulty=${filter.value}` : '';
      const data = await http.get('/api/problems' + q);
      problems.value = data || [];
    } finally {
      loading.value = false;
    }
  }
  return { problems, filter, loading, loadProblems };
}
