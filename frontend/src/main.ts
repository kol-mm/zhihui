import { createApp } from 'vue';
import { ElBadge } from 'element-plus/es/components/badge/index.mjs';
import { ElButton } from 'element-plus/es/components/button/index.mjs';
import { ElDialog } from 'element-plus/es/components/dialog/index.mjs';
import { ElDivider } from 'element-plus/es/components/divider/index.mjs';
import { ElDrawer } from 'element-plus/es/components/drawer/index.mjs';
import { ElDropdown, ElDropdownItem, ElDropdownMenu } from 'element-plus/es/components/dropdown/index.mjs';
import { ElEmpty } from 'element-plus/es/components/empty/index.mjs';
import { ElForm, ElFormItem } from 'element-plus/es/components/form/index.mjs';
import { ElIcon } from 'element-plus/es/components/icon/index.mjs';
import { ElInput } from 'element-plus/es/components/input/index.mjs';
import { ElInputNumber } from 'element-plus/es/components/input-number/index.mjs';
import { ElOption, ElSelect } from 'element-plus/es/components/select/index.mjs';
import { ElRadio, ElRadioButton, ElRadioGroup } from 'element-plus/es/components/radio/index.mjs';
import { ElSlider } from 'element-plus/es/components/slider/index.mjs';
import { ElSwitch } from 'element-plus/es/components/switch/index.mjs';
import { ElTable, ElTableColumn } from 'element-plus/es/components/table/index.mjs';
import { ElTabPane, ElTabs } from 'element-plus/es/components/tabs/index.mjs';
import { ElTag } from 'element-plus/es/components/tag/index.mjs';
import { ElUpload } from 'element-plus/es/components/upload/index.mjs';
import 'element-plus/es/components/badge/style/css';
import 'element-plus/es/components/button/style/css';
import 'element-plus/es/components/dialog/style/css';
import 'element-plus/es/components/divider/style/css';
import 'element-plus/es/components/drawer/style/css';
import 'element-plus/es/components/dropdown/style/css';
import 'element-plus/es/components/empty/style/css';
import 'element-plus/es/components/form/style/css';
import 'element-plus/es/components/form-item/style/css';
import 'element-plus/es/components/icon/style/css';
import 'element-plus/es/components/input/style/css';
import 'element-plus/es/components/input-number/style/css';
import 'element-plus/es/components/radio/style/css';
import 'element-plus/es/components/radio-button/style/css';
import 'element-plus/es/components/radio-group/style/css';
import 'element-plus/es/components/select/style/css';
import 'element-plus/es/components/option/style/css';
import 'element-plus/es/components/slider/style/css';
import 'element-plus/es/components/switch/style/css';
import 'element-plus/es/components/table/style/css';
import 'element-plus/es/components/table-column/style/css';
import 'element-plus/es/components/tabs/style/css';
import 'element-plus/es/components/tab-pane/style/css';
import 'element-plus/es/components/tag/style/css';
import 'element-plus/es/components/upload/style/css';
import 'element-plus/es/components/dropdown-item/style/css';
import 'element-plus/es/components/dropdown-menu/style/css';
import 'element-plus/es/components/message/style/css';
import 'element-plus/es/components/message-box/style/css';
import './styles/main.css';
import App from './App.vue';

const app = createApp(App);
[
  ElBadge, ElButton, ElDialog, ElDivider, ElDrawer, ElDropdown, ElDropdownItem, ElDropdownMenu,
  ElEmpty, ElForm, ElFormItem, ElIcon, ElInput, ElInputNumber, ElOption, ElRadio, ElRadioButton,
  ElRadioGroup, ElSelect, ElSlider, ElSwitch, ElTable, ElTableColumn, ElTabPane, ElTabs, ElTag, ElUpload
].forEach(component => app.use(component));
app.mount('#app');
