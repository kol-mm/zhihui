import { createApp } from 'vue';
import { ElButton, ElTable, ElTableColumn, ElTag } from 'element-plus';
import 'element-plus/dist/index.css';
import './styles/main.css';
import App from './App.vue';

createApp(App)
  .use(ElButton)
  .use(ElTable)
  .use(ElTableColumn)
  .use(ElTag)
  .mount('#app');
