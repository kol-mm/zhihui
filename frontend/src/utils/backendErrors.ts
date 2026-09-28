export const GENERIC_ERROR_MESSAGE = '操作失败，请稍后重试';

/** Said when a form breaks a rule the page has no specific words for. */
export const INVALID_INPUT_MESSAGE = '提交的内容不符合要求，请检查后重试';

/**
 * What the services' fields are called on the page, for FastAPI's validation errors. Only fields a person can
 * actually type into are listed; anything else gets INVALID_INPUT_MESSAGE.
 */
const FIELD_LABELS: Record<string, string> = {
  question: '问题',
  title: '标题',
  name: '名称',
  platform_name: '平台名称',
  platform_notice: '平台公告',
};

type ValidationProblem = { loc?: unknown; type?: unknown; ctx?: { max_length?: unknown; min_length?: unknown } };

/**
 * The words a failed response carries, whichever service sent it. The Java services answer with `message`;
 * ai-service is FastAPI, whose errors carry `detail` — a sentence for the errors it raises itself, and a list of
 * problems when a request fails validation. Reading only `message` turned every ai-service refusal, including
 * ones already written in Chinese, into 操作失败.
 */
export function backendMessage(body: unknown): string {
  if (typeof body === 'string') return body;
  if (!body || typeof body !== 'object') return '';
  const { message, detail } = body as { message?: unknown; detail?: unknown };
  if (typeof message === 'string' && message.trim()) return message;
  if (typeof detail === 'string') return detail;
  if (Array.isArray(detail)) return describeValidation(detail as ValidationProblem[]);
  return '';
}

/** The first validation problem, in words; the page shows one message at a time anyway. */
function describeValidation(problems: ValidationProblem[]): string {
  const problem = problems[0];
  if (!problem) return INVALID_INPUT_MESSAGE;
  const path = Array.isArray(problem.loc) ? problem.loc : [];
  const field = String(path[path.length - 1] ?? '');
  const label = FIELD_LABELS[field];
  if (!label) return INVALID_INPUT_MESSAGE;
  const max = Number(problem.ctx?.max_length);
  if (problem.type === 'string_too_long' && Number.isFinite(max)) return `${label}不能超过 ${max} 个字符`;
  if (problem.type === 'missing' || problem.type === 'string_too_short') return `请填写${label}`;
  return `${label}格式不正确`;
}

const EXACT: Record<string, string> = {
  'captcha is required or invalid; please obtain a new captcha': '验证码错误或已失效，已为你更换验证码',
  'too many requests': '操作过于频繁，请稍后再试',
  'valid user authorization is required': '请先登录后再操作',
  'admin authorization is required': '需要管理员权限',
  'internal authorization is required': GENERIC_ERROR_MESSAGE,
  'user not found': '用户不存在',
  'post not found': '帖子不存在或暂不可查看',
  'knowledge file not found': '知识文件不存在',
  'chat session not found': '私信会话不存在',
  'notification not found': '通知不存在',
  'ticket not found': '反馈工单不存在',
  'report not found': '举报记录不存在',
  'draft not found': '草稿不存在',
  'file is required': '请选择文件',
  'comment content is required': '请输入评论内容',
  'message content is required': '请输入消息内容',
  'category name is required': '请输入分类名称',
  'community feature is disabled': '社区功能已关闭',
  'notifications feature is disabled': '通知功能已关闭',
  'access to this user is denied': '无权访问该用户数据',
  'access to this post is denied': '无权访问该帖子',
  'access to this draft is denied': '无权访问该草稿',
  'invalid post status': '帖子状态不正确',
  'unknown error': GENERIC_ERROR_MESSAGE,
  // ai-service
  'AI session not found': 'AI 会话不存在或已被删除',
  'access to this AI session is denied': '无权访问该 AI 会话',
  'session title is required': '请输入会话标题',
  'AI request URL must be an absolute http or https URL': '接口地址必须是以 http:// 或 https:// 开头的完整地址',
  'AI request URL must not contain embedded credentials': '接口地址中不能包含用户名或密码',
  'AI request URL contains an invalid port': '接口地址的端口不正确',
  'AI request URL must use https': '接口地址必须使用 https',
  'private AI upstream addresses are disabled': '不能使用内网或本机地址作为模型接口',
  'AI upstream hostname could not be resolved': '无法解析接口地址的域名，请检查地址是否正确',
};

/** A service's words as the page may show them: translated when known, kept when already safe Chinese. */
export function localizeBackendMessage(message: string): string {
  const text = message.trim();
  if (EXACT[text]) return EXACT[text];
  if (/^select between 1 and \d+ images$/i.test(text)) return '请选择规定数量的图片';
  if (text.startsWith('image upload failed:')) return '图片上传失败，请检查文件后重试';
  return isSafeChineseMessage(text) ? text : GENERIC_ERROR_MESSAGE;
}

export function isSafeChineseMessage(message: string): boolean {
  if (!message || message.length > 160 || !/[㐀-鿿]/.test(message)) return false;
  return !/(?:https?:\/\/|localhost|\b\d{1,3}(?:\.\d{1,3}){3}\b|[a-z]:[\\/]|[\\/](?:api|user|knowledge|post|message|ai)\b|exception|stack|trace|sql|database|table|com\.aiknowledge|org\.springframework|\r|\n)/i.test(message);
}
