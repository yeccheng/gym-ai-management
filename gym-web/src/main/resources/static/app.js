/**
 * 智慧健身管理系统 · 前端入口（Vue 3，无构建步骤，运行时由 Spring Boot 静态托管）
 *
 * 三个要点：
 * 1. SSE 不能用原生 EventSource —— 它发不出 Authorization 头。
 *    所以这里用 fetch + ReadableStream 手动解析 SSE 帧，才能在请求头里带上 JWT。
 * 2. 模型「先调工具、再作答」，工具调用期间服务端一个字都不推。
 *    所以首字到达前必须显式给用户一个「正在查数据」的状态，否则界面像卡死。
 *    若后端推送 tool_call / tool_result JSON 事件，前端单独渲染轨迹，不走 appendAnswer。
 * 3. 401 一律回到未登录态：JWT 会过期，过期的表现就是接口突然全部 401。
 */
const { createApp, ref, reactive, computed, nextTick, onMounted, watch } = Vue;

const CHAT_HISTORY_KEY_PREFIX = 'gym_chat_history_';
const CHAT_HISTORY_MAX = 20;
/** 按登录用户隔离对话，避免新号打开还看见 demo 的「破纪录 / 88.7」 */
function chatHistoryKey(uid) {
    return CHAT_HISTORY_KEY_PREFIX + (uid || 'anon');
}

try {
createApp({
    setup() {
        const base = location.origin;
        const token = ref(localStorage.getItem('gym_token') || '');
        // 过期先亮横幅，不立刻清 token 踢回登录页——组间/问 AI 中途最怕这个
        const authExpired = ref(false);
        const loggedIn = computed(() => token.value.length > 0);

        // 角色：JWT payload 里有 role 声明（1管理员 2教练 3会员），前端解出来做界面级权限。
        function parseJwt(t) {
            try {
                const p = t.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
                const padded = p + '='.repeat((4 - p.length % 4) % 4);
                const bin = atob(padded);
                const bytes = Uint8Array.from(bin, c => c.charCodeAt(0));
                return JSON.parse(new TextDecoder('utf-8').decode(bytes));
            } catch (e) { return {}; }
        }
        function parseRole(t) { return Number(parseJwt(t).role) || 0; }
        function parseUsername(t) { return parseJwt(t).username || '会员'; }
        function parseUserId(t) { return String(parseJwt(t).sub || ''); }
        /** 招呼优先用 displayName（真实姓名），永远别把登录名 demo 贴脸上 */
        function parseDisplayName(t) {
            const p = parseJwt(t);
            let n = (p.displayName || p.realName || p.username || '铁友').trim();
            if (!n || /^demo$/i.test(n)) n = '铁友';
            return n;
        }
        const role = ref(parseRole(token.value));
        const displayName = ref(token.value ? parseDisplayName(token.value) : '铁友');
        const currentUserId = ref(token.value ? parseUserId(token.value) : '');
        const isAdmin = computed(() => role.value === 1);
        const roleText = computed(() => role.value === 1 ? '管理员' : role.value === 2 ? '教练' : '会员');
        // 会员主路径不提「后台角色」；管理员才标明身份，避免训练台看起来像 ERP
        const memberHello = computed(() => {
            if (isAdmin.value) return '你好，' + displayName.value + ' · 管理员';
            if (role.value === 2) return '你好，' + displayName.value + ' · 教练';
            return '你好，' + displayName.value + ' · 今天练哪';
        });

        const tab = ref('train');
        const userId = computed(() => Number(parseUserId(token.value)) || 0);
        /** list | mine | fav | detail | compose */
        const forumView = ref('list');
        const forumSort = ref('new');
        const forumCategory = ref(0);
        const forumPosts = ref([]);
        const forumDetail = ref(null);
        const forumBusy = ref(false);
        const forumCommentText = ref('');
        const forumReplyTo = ref(null);
        const forumForm = reactive({ title: '', content: '', category: 4 });
        const adminForumPosts = ref([]);
        const adminForumBusy = ref(false);
        const adminForumStatus = ref(0);
        /** mine | history | plan | venue */
        const meSub = ref('mine');
        const habitStats = ref(null);
        const weeklyReviewText = ref('');
        const reviewBusy = ref(false);
        const historyList = ref([]);
        const historyBusy = ref(false);
        const historyOpenId = ref('');
        const historyDetail = ref(null);
        const planSaving = ref(false);
        const planDirty = ref(false);
        const hasRealTrainingData = ref(false);
        const chartLoadError = ref('');       // 有历史却画不出来时，别装成「还没有曲线」
        const lastGoodCharts = ref(null);     // 上次成功的曲线，失败时保底
        const muscleVolume = ref({ available: false, weeks: 4, items: [], reason: '' });
        const plateauAlerts = ref([]);        // 「我的」平台期提示卡
        const bodyWeightSeries = ref([]);     // 体重曲线点
        const dietDay = ref({ date: '', totalCalories: 0, totalProteinG: 0, items: [] });
        const dietForm = reactive({ foodName: '', calories: '', proteinG: '', mealType: 4 });
        const dietBusy = ref(false);
        const bodySnap = ref({ available: false, date: null, weightKg: null, bodyFatPct: null });
        const bodyForm = reactive({ weightKg: '' });
        const bodyBusy = ref(false);
        const profile = ref({
            displayName: '', heightCm: null, weightKg: null, targetWeightKg: null,
            goal: 3, trainLevel: 1, injuryNote: '', trainingMonths: null,
            trainingYears: null, joinDate: null, calorieGoal: null, proteinGoalG: null
        });
        const profileForm = reactive({
            displayName: '', heightCm: '', goal: 3, trainLevel: 1, injuryNote: '',
            targetWeightKg: '', trainingYears: '', calorieGoal: '2200', proteinGoalG: '160',
            age: '25', sex: 'male', activityLevel: 'moderate'
        });
        const profileBusy = ref(false);
        const nutriBusy = ref(false);
        const nutriSuggest = ref(null);
        const accountBusy = ref(false);
        const accountInfo = reactive({
            username: '', realName: '', phoneBound: false,
            phone: null, phoneMasked: null, hint: ''
        });
        const bindForm = reactive({ phone: '', code: '' });
        const pwdForm = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' });
        const onboard = reactive({
            open: false, busy: false,
            displayName: '', goal: 3, trainLevel: 1,
            heightCm: '', weightKg: '', age: '25',
            applyNutrition: true
        });
        const seedBusy = ref(false);
        const nextSessionTip = ref('');
        const weekPlan = ref({ intensityFactor: 1, intensityReason: '', volumeGaps: [], days: [] });
        const reminders = ref([]);
        const venueCourses = ref([]);
        const venueBookings = ref([]);
        const venueCards = ref([]);
        const venueConsume = ref([]);
        const venueBusy = ref(false);
        const venueMsg = ref('');
        const dietHistory = ref([]);
        const knowledgeQ = ref('杠铃卧推常见错误和呼吸要点');
        const knowledgeA = ref('');
        const knowledgeBusy = ref(false);
        const intensityHint = ref({ factor: 1, reason: '', deviationCount: 0 });
        // localStorage 脏数据绝不能让 JSON.parse 把整个 Vue setup 炸死——
        // 否则 #app 挂不上，手机端就会整页露出 {{ t.msg }} / {{ dialog.message }}。
        function readJsonStorage(key, fallback) {
            try {
                const raw = localStorage.getItem(key);
                if (raw == null || raw === '') return fallback;
                return JSON.parse(raw);
            } catch (_) {
                try { localStorage.removeItem(key); } catch (__) {}
                return fallback;
            }
        }
        const planDone = ref(readJsonStorage('gym_plan_done', {}));
        const dismissedReminders = ref(readJsonStorage('gym_remind_dismiss', []));
        const dietFavs = ref(readJsonStorage('gym_diet_favs', []));
        const offlineQueue = ref(readJsonStorage('gym_offline_sets', []));
        const selfCheck = ref({ items: [], running: false });
        const visibleReminders = computed(() =>
            (reminders.value || []).filter(t => !(dismissedReminders.value || []).includes(t)));
        const foodDb = Array.isArray(window.GYM_FOOD_DB) ? window.GYM_FOOD_DB : [];
        const dietFoodQ = ref('');
        const dietCat = ref('');
        const dietSearchOpen = ref(false);
        const dietCats = computed(() => {
            const set = new Set(foodDb.map(f => f.cat).filter(Boolean));
            return ['', ...Array.from(set)];
        });
        const dietQuickFoods = computed(() => {
            const pins = ['鸡胸肉 150g', '米饭一碗（约 150g）', '蛋白粉 1 勺（约 30g）',
                '鸡蛋 2 个', '香蕉 1 根', '燕麦片 50g（干）', '希腊酸奶 150g', '轻食鸡胸便当'];
            return pins.map(n => foodDb.find(f => f.name === n)).filter(Boolean);
        });
        const filteredFoods = computed(() => {
            const q = (dietFoodQ.value || '').trim().toLowerCase();
            const cat = dietCat.value;
            let list = foodDb;
            if (cat) list = list.filter(f => f.cat === cat);
            if (q) {
                list = list.filter(f => (f.name || '').toLowerCase().indexOf(q) >= 0
                    || (f.cat || '').indexOf(q) >= 0);
            } else if (!cat) {
                list = dietQuickFoods.value.concat(
                    foodDb.filter(f => !dietQuickFoods.value.some(p => p.name === f.name)).slice(0, 12)
                );
            }
            return list.slice(0, 24);
        });
        // 兼容旧引用
        const dietPresets = dietQuickFoods;
        // 强制流式：工具证据必须看得见；不再提供 sync 开关以免答辩像「没查库」
        const mode = ref('stream');
        const demoStoryLock = ref(true); // demo 账号记卧推/肩推前提醒，防冲剧本
        const restPrefSec = ref(Number(localStorage.getItem('gym_rest_sec') || 90));
        const restCustomSec = ref(String(localStorage.getItem('gym_rest_sec') || 90));
        const question = ref('');
        const messages = ref([]);
        const streaming = ref(false);
        const hasFirstChunk = ref(false);
        const busy = ref(false);
        const loggingIn = ref(false);
        const loginError = ref('');
        const authMode = ref('login'); // login | register | reset
        const loginWay = ref('password'); // password | sms（仅 login）
        const authFormKey = ref(String(Date.now()));
        const loginTouched = reactive({ username: false, password: false });
        const smsCountdown = ref(0);
        const smsSending = ref(false);
        let smsTimer = null;
        const toolCount = ref(null);
        const highlights = ref([]);
        const demoResult = ref('');
        const chatWindow = ref(null);
        const chart1El = ref(null);       // 主力力量曲线
        const chart2El = ref(null);       // 真实周容量
        const chart3El = ref(null);       // 体重曲线
        const chartDemoBig3 = ref(null);  // 无数据时的示例图
        const chartDemoVol = ref(null);
        const chartNotes = ref([]);       // 减载 / 缺练人话解释
        const chartSparse = ref(false);   // 新号只有起点：别装成空白成绩单
        const chartSparseTip = ref('');
        let chart1Inst = null;
        let chart2Inst = null;
        let chart3Inst = null;
        let chartDemoBig3Inst = null;
        let chartDemoVolInst = null;

        // 训练打卡
        const trainBusy = ref(false);
        const lastSession = reactive({ date: '', sessionId: null, exercises: [] });
        const today = reactive({ sessionId: null, date: '', sets: [] });
        const exerciseList = ref([]);
        const recentExercises = ref([]); // 近几周常用，快捷栏
        const exTag = ref(''); // 空 = 全部，进页不要默认筛空分类
        const exKeyword = ref('');
        // 冷启动默认 true：组间进页先转圈，绝不闪「还没有可选动作」
        const exercisesLoading = ref(true);
        let exerciseLoadSeq = 0; // 并发只认最后一次，旧响应不许冲空列表
        const customExBusy = ref(false);
        const customEx = reactive({
            open: false,
            name: '',
            equipment: 1,
            primaryMuscle: '胸'
        });
        const exerciseListExpanded = ref(false);
        const exercisePreviewLimit = 12;
        /** 默认露前 12 个；搜索时自动展开，别翻两次才找到肩推 */
        const visibleExercises = computed(() => {
            const searching = !!(exKeyword.value && exKeyword.value.trim());
            if (exerciseListExpanded.value || searching
                    || exerciseList.value.length <= exercisePreviewLimit) {
                return exerciseList.value;
            }
            return exerciseList.value.slice(0, exercisePreviewLimit);
        });
        /** 快捷栏：三大项 + 最近常用（去重），卧推不用翻长列表 */
        const quickExercises = computed(() => {
            const out = [];
            const seen = new Set();
            const push = (ex) => {
                if (!ex || ex.id == null) return;
                const k = String(ex.id);
                if (seen.has(k)) return;
                seen.add(k);
                out.push(ex);
            };
            const pinNames = ['杠铃卧推', '杠铃深蹲', '硬拉', '卧推', '深蹲'];
            for (const name of pinNames) {
                const hit = exerciseList.value.find(e => e.name === name)
                    || recentExercises.value.find(e => e.name === name);
                if (hit) push(hit);
            }
            recentExercises.value.forEach(push);
            return out.slice(0, 6);
        });
        function shortExName(name) {
            if (!name) return '';
            return name.replace(/^杠铃/, '').replace(/^哑铃/, '');
        }
        // 组盘默认关闭：选动作后以底部弹层打开（体检报告 P0-3）
        const pad = reactive({
            open: false, exerciseId: null, name: '', weight: '', reps: '',
            setId: null, hint: '填写重量和次数后保存',
            lastWeightKg: null, lastReps: null, // 「上次重量」快捷标签
            suggest: null,   // 下一组建议重量
            rpe: null,       // 可选 6～10
            warmup: false,   // 热身组不计入有效容量
            cueOpen: false,
            cue: null        // { steps, commonMistakes, breathing, safetyTips }
        });
        // 组间休息钟：保存成功后倒计时，重度用户最离不开
        const rest = reactive({ active: false, totalSec: 90, leftSec: 0, label: '' });
        let restTimer = null;
        // 今日列表默认折叠，避免占满打卡屏
        const todayListOpen = ref(false);
        // 扩展区默认折叠：计划偏差 / 教练提醒不抢核心记组流程
        const moreToolsOpen = ref(false);
        // 点开某一组才看 e1RM 详情
        const expandedSetId = ref(null);
        const injuryWarn = ref('');
        const devMsg = ref('');
        let padTimer = null;

        // P1-5 Toast
        const toasts = ref([]);
        let toastSeq = 0;

        // P1-6 自检状态灯
        const health = reactive({
            ai: null, mysql: null, redis: null, tools: null,
            toolCount: 0, checking: false
        });

        // P2-2 操作列「更多」菜单
        const moreMenu = ref(null);

        // 场馆管理
        const gymLoaded = ref(false);
        const gymBusy = ref(false);
        const gymMsg = ref('');
        const members = ref([]);
        const coaches = ref([]);
        const courses = ref([]);
        const equipment = ref([]);
        const bookings = ref([]);
        const stats = ref(null);
        const gymTab = ref('member');
        const cards = ref([]);
        const consumeRecords = ref([]);
        const form = reactive({ open: false });
        const formEntity = ref('member');
        const formId = ref(null);
        const formModel = reactive({});

        // 默认空着；浏览器若自动填 demo，挂载后还会再清一次
        const loginForm = reactive({
            username: '', password: '', realName: '', confirmPassword: '',
            phone: '', smsCode: ''
        });
        // P1-7：登录 / 记组 / CRUD 字段级错误
        const loginFieldErrors = reactive({
            username: '', password: '', realName: '', confirmPassword: '',
            phone: '', smsCode: ''
        });
        const padFieldErrors = reactive({ weight: '', reps: '' });
        const formErrors = reactive({});
        const padSaving = ref(false);
        function isPhoneAccount(v) {
            return /^1\d{10}$/.test(String(v || '').trim());
        }
        function clearLoginFieldErrors() {
            loginFieldErrors.username = '';
            loginFieldErrors.password = '';
            loginFieldErrors.realName = '';
            loginFieldErrors.confirmPassword = '';
            loginFieldErrors.phone = '';
            loginFieldErrors.smsCode = '';
        }
        function scrubLoginForm() {
            loginForm.username = '';
            loginForm.password = '';
            loginForm.realName = '';
            loginForm.confirmPassword = '';
            loginForm.phone = '';
            loginForm.smsCode = '';
            clearLoginFieldErrors();
            loginError.value = '';
            loginTouched.username = false;
            loginTouched.password = false;
        }
        /** 浏览器若偷偷填了 demo，用户没动过输入框时直接清掉 */
        function purgeAutofillDemo() {
            if (!loginTouched.username && /^demo$/i.test((loginForm.username || '').trim())) {
                loginForm.username = '';
            }
            if (!loginTouched.password && /^(demo123|demo)$/i.test(loginForm.password || '')) {
                loginForm.password = '';
            }
        }
        function onLoginUsernameInput() {
            loginTouched.username = true;
            loginFieldErrors.username = '';
        }
        function onLoginPasswordInput() {
            loginTouched.password = true;
            loginFieldErrors.password = '';
        }
        function switchAuthMode(mode) {
            if (mode === 'register') authMode.value = 'register';
            else if (mode === 'reset') authMode.value = 'reset';
            else authMode.value = 'login';
            if (authMode.value === 'login') loginWay.value = 'password';
            authFormKey.value = String(Date.now());
            scrubLoginForm();
        }

        const authPageTitle = computed(() => {
            if (authMode.value === 'register') return '注册';
            if (authMode.value === 'reset') return '找回密码';
            return '登录';
        });
        const authSubmitLabel = computed(() => {
            if (loggingIn.value) {
                if (authMode.value === 'register') return '注册中…';
                if (authMode.value === 'reset') return '重置中…';
                return '登录中…';
            }
            if (authMode.value === 'register') return '注册并进入';
            if (authMode.value === 'reset') return '重置并进入';
            if (loginWay.value === 'sms') return '验证码登录';
            return '进入训练台';
        });
        const smsBtnText = computed(() => {
            if (smsSending.value) return '发送中…';
            if (smsCountdown.value > 0) return smsCountdown.value + 's';
            return '获取验证码';
        });

        function startSmsCountdown(sec) {
            smsCountdown.value = sec || 60;
            if (smsTimer) clearInterval(smsTimer);
            smsTimer = setInterval(() => {
                smsCountdown.value -= 1;
                if (smsCountdown.value <= 0) {
                    smsCountdown.value = 0;
                    clearInterval(smsTimer);
                    smsTimer = null;
                }
            }, 1000);
        }

        async function sendSmsCode(scene) {
            const fromBind = scene === 'bind';
            const phone = fromBind
                ? (bindForm.phone || '').trim()
                : (loginForm.phone || loginForm.username || '').trim();
            if (!isPhoneAccount(phone)) {
                if (fromBind) {
                    showToast('请输入 11 位手机号', 'error');
                } else if (authMode.value === 'register' && !loginForm.phone) {
                    loginFieldErrors.username = '请先填写 11 位手机号';
                } else {
                    loginFieldErrors.phone = '请输入 11 位手机号';
                }
                return;
            }
            // 注册页用 username 当手机号时，同步到 phone 便于提交
            if (!fromBind && !loginForm.phone) loginForm.phone = phone;
            smsSending.value = true;
            if (!fromBind) loginError.value = '';
            try {
                const headers = fromBind
                    ? authHeaders()
                    : { 'Content-Type': 'application/json' };
                const res = await fetch(base + '/auth/sms/send', {
                    method: 'POST',
                    headers,
                    body: JSON.stringify({ phone, scene })
                });
                const data = await res.json().catch(() => ({}));
                if (data.code === 200) {
                    startSmsCountdown(60);
                    const hint = (data.data && data.data.hint)
                        || (data.data && data.data.demoCode
                            ? ('演示验证码 ' + data.data.demoCode)
                            : '验证码已发送');
                    showToast(hint, 'success');
                } else {
                    const msg = (data && data.message) ? data.message : '验证码发送失败';
                    if (fromBind) showToast(msg, 'error');
                    else loginError.value = msg;
                }
            } catch (e) {
                if (fromBind) showToast('网络不稳定，请再试一次', 'error');
                else loginError.value = '网络不稳定，请再试一次';
                console.warn('sms send error', e);
            } finally {
                smsSending.value = false;
            }
        }

        /** 答辩 / 第一次进门：一键填体验账号，别干瞪眼猜密码 */
        function fillDemoAccount() {
            authMode.value = 'login';
            loginWay.value = 'password';
            authFormKey.value = String(Date.now());
            loginForm.username = 'demo';
            loginForm.password = 'demo123';
            loginForm.phone = '';
            loginForm.smsCode = '';
            loginTouched.username = true;
            loginTouched.password = true;
            clearLoginFieldErrors();
            loginError.value = '';
        }

        function submitAuth() {
            if (authMode.value === 'register') return register();
            if (authMode.value === 'reset') return resetPassword();
            if (loginWay.value === 'sms') return loginBySms();
            return login();
        }

        const todayHeroLine = computed(() => {
            const n = today.sets.length;
            if (!n) return '选动作 → 记重量次数 → 一组搞定';
            const names = [];
            const seen = new Set();
            today.sets.forEach(s => {
                const name = s.exerciseName || '';
                if (!name || seen.has(name)) return;
                seen.add(name);
                names.push(name.replace(/^杠铃/, ''));
            });
            const who = names.slice(0, 2).join('、') || '训练';
            return '今天已练 ' + who + ' · 共 ' + n + ' 组';
        });
        const todaySummaryLine = computed(() => {
            if (!today.sets.length) return '';
            const last = today.sets[today.sets.length - 1];
            return (last.exerciseName || '动作') + ' 最近一组 '
                + last.weightKg + 'kg × ' + last.reps
                + (today.sets.length > 1 ? '（共 ' + today.sets.length + ' 组）' : '');
        });
        /** 同文案说明只留一条，避免减载提示复读 */
        const chartNotesDisplay = computed(() => {
            const raw = chartNotes.value || [];
            const out = [];
            const seen = new Set();
            raw.forEach(n => {
                const key = (n.kind || '') + '|' + (n.label || '');
                if (seen.has(key)) return;
                seen.add(key);
                out.push(n);
            });
            return out;
        });

        // P1-8：自定义确认弹窗（替代原生 confirm / prompt）
        const dialog = reactive({
            open: false, title: '确认', message: '', needInput: false,
            inputLabel: '', inputValue: '', inputError: '', _resolve: null
        });

        function askConfirm(message, title) {
            return new Promise(resolve => {
                dialog.open = true;
                dialog.title = title || '确认';
                dialog.message = message;
                dialog.needInput = false;
                dialog.inputError = '';
                dialog._resolve = resolve;
            });
        }

        function askPrompt(message, defaultVal, inputLabel, title) {
            return new Promise(resolve => {
                dialog.open = true;
                dialog.title = title || '请输入';
                dialog.message = message;
                dialog.needInput = true;
                dialog.inputLabel = inputLabel || '数值';
                dialog.inputValue = defaultVal == null ? '' : String(defaultVal);
                dialog.inputError = '';
                dialog._resolve = resolve;
            });
        }

        function resolveDialog(ok) {
            if (ok && dialog.needInput) {
                const n = Number(dialog.inputValue);
                if (!Number.isFinite(n) || dialog.inputValue === '') {
                    dialog.inputError = '请输入有效数字';
                    return;
                }
            }
            const resolve = dialog._resolve;
            dialog.open = false;
            dialog._resolve = null;
            if (!resolve) return;
            if (!ok) resolve(dialog.needInput ? null : false);
            else resolve(dialog.needInput ? String(dialog.inputValue) : true);
        }

        const presets = [
            '我肩膀和腘绳肌练得够吗',
            '卧推涨没涨？下次举多少',
            '深蹲涨没涨？下次举多少',
            '我体重变化怎么样',
            '杠铃卧推常见错误和呼吸要点'
        ];

        /** 「我的」页顶栏一句缺口，不用先点开 AI */
        const volumeGapLine = computed(() => {
            const items = (muscleVolume.value && muscleVolume.value.items) || [];
            const gaps = items.filter(it => it.verdict === 'BELOW_MEV');
            if (!gaps.length) return '';
            return '本周缺口：' + gaps.map(it => {
                const need = Math.max(0, (it.mev || 0) - (it.setsPerWeek || 0));
                return it.muscle + (need > 0 ? ('还差约 ' + need + ' 组') : '');
            }).join(' · ');
        });

        function askVolumeGap() {
            const items = (muscleVolume.value && muscleVolume.value.items) || [];
            const gaps = items.filter(it => it.verdict === 'BELOW_MEV');
            const names = gaps.map(it => it.muscle).filter(Boolean);
            question.value = names.length
                ? ('我' + names.slice(0, 3).join('和') + '练得够吗')
                : '我肩膀和腘绳肌练得够吗';
            tab.value = 'chat';
            nextTick(() => send());
        }

        function askPlateau(alert) {
            const name = (alert && alert.exerciseName) || '卧推';
            if (alert && alert.justBrokePlateau) {
                question.value = name + '是刚破平台期吗？之前卡了几周';
            } else {
                question.value = '我的' + name + '是不是卡住了；下周具体怎么改';
            }
            tab.value = 'chat';
            nextTick(() => send());
        }

        /** 平台期卡片上的固定「下周怎么改」，不强制先聊 AI */
        function plateauNextPlan(a) {
            if (!a) return '';
            if (a.justBrokePlateau) {
                return '下周：新重量先巩固 2～3 组×5，不要连着冲 PR；辅助保持。';
            }
            const n = a.exerciseName || '';
            if (n.indexOf('卧推') >= 0 || n.indexOf('肩推') >= 0 || n.indexOf('推') >= 0) {
                return '下周：主项改暂停/窄握变式，重量约 90%；侧平举+面拉各加 1 组；推类总量先别加。';
            }
            if (n.indexOf('蹲') >= 0 || n.indexOf('拉') >= 0) {
                return '下周：该动作减载一练（约 85%），换站距/握距；或加 1 组腿弯举/直腿硬拉辅助。';
            }
            return '下周：该动作减载一练（约 85%），换变式刺激；有效组先维持再谈加量。';
        }

        function applyPlateauPlan(a) {
            const plan = plateauNextPlan(a);
            const name = (a && a.exerciseName) || '该动作';
            question.value = name + '卡住了。请按这个执行并补充细节：' + plan;
            tab.value = 'chat';
            nextTick(() => send());
        }

        const canSend = computed(() =>
            loggedIn.value && !streaming.value && question.value.trim().length > 0);

        let controller = null;
        // P2-4：打字机队列，保证 chunk 按序逐字渲染
        let typeQueue = Promise.resolve();

        // ---------- Toast / 通知 ----------
        function showToast(msg, type) {
            const id = ++toastSeq;
            toasts.value.push({ id, msg, type: type === 'error' ? 'error' : 'success' });
            setTimeout(() => {
                toasts.value = toasts.value.filter(t => t.id !== id);
            }, 3000);
        }

        function healthClass(v) {
            if (v === true) return 'ok';
            if (v === false) return 'bad';
            return '';
        }

        // ---------- 认证 ----------
        function applyAuthToken(jwt, welcome, opts) {
            token.value = jwt;
            localStorage.setItem('gym_token', jwt);
            authExpired.value = false;
            role.value = parseRole(jwt);
            displayName.value = parseDisplayName(jwt);
            currentUserId.value = parseUserId(jwt);
            loginError.value = '';
            authMode.value = 'login';
            scrubLoginForm();
            // 换账号先清空屏幕，再恢复「这个人」自己的历史——别把 demo 的破纪录贴过来
            messages.value = [];
            restoreChatHistory();
            // 会员/教练登录一律进打卡台；后台只给管理员，别抢主路径
            if (!isAdmin.value) {
                tab.value = 'train';
                meSub.value = 'mine';
            } else if (tab.value === 'gym') {
                /* 管理员可留在后台 */
            } else {
                tab.value = 'train';
            }
            loadToolCount();
            loadTrainPage();
            loadAccountInfo();
            showToast(welcome || ('欢迎，' + displayName.value), 'success');
            if (opts && opts.onboard) {
                openOnboard();
            }
        }

        function openOnboard() {
            onboard.displayName = displayName.value === '铁友' ? '' : (displayName.value || '');
            onboard.goal = 2; // 默认引导减脂更常见；可改
            onboard.trainLevel = 1;
            onboard.heightCm = profileForm.heightCm || '';
            onboard.weightKg = bodyForm.weightKg || '';
            onboard.age = profileForm.age || '25';
            onboard.applyNutrition = true;
            onboard.busy = false;
            onboard.open = true;
        }
        function skipOnboard() {
            onboard.open = false;
            showToast('可随时在「我的」完善昵称与目标', 'success');
        }
        async function finishOnboard() {
            onboard.busy = true;
            try {
                const name = (onboard.displayName || '').trim();
                profileForm.displayName = name || profileForm.displayName || displayName.value;
                profileForm.goal = onboard.goal;
                profileForm.trainLevel = onboard.trainLevel;
                if ((onboard.heightCm || '').trim()) profileForm.heightCm = String(onboard.heightCm).trim();
                if ((onboard.age || '').trim()) profileForm.age = String(onboard.age).trim();
                await saveProfile();
                if (name) displayName.value = name;

                if (onboard.applyNutrition) {
                    const w = parseFloat(onboard.weightKg);
                    if (!Number.isNaN(w) && w >= 30) {
                        // 先落一笔体重，供公式与饮食进度共用
                        try {
                            await apiJson(base + '/training/body', {
                                method: 'POST',
                                body: JSON.stringify({ weightKg: w })
                            });
                        } catch (_) { /* 体重写入失败不挡营养 */ }
                    }
                    await suggestNutrition(true, {
                        heightCm: parseFloat(onboard.heightCm),
                        weightKg: parseFloat(onboard.weightKg),
                        age: parseInt(onboard.age, 10),
                        goal: onboard.goal
                    });
                }
                onboard.open = false;
                showToast(onboard.applyNutrition ? '档案与营养目标已就绪' : '档案已就绪，去记第一组吧', 'success');
            } finally {
                onboard.busy = false;
            }
        }

        async function loadAccountInfo() {
            if (!loggedIn.value) return;
            try {
                const hit = await apiJson(base + '/auth/account');
                if (!hit.ok || !hit.body || !hit.body.data) return;
                const d = hit.body.data;
                accountInfo.username = d.username || '';
                accountInfo.realName = d.realName || '';
                accountInfo.phoneBound = !!d.phoneBound;
                accountInfo.phone = d.phone || null;
                accountInfo.phoneMasked = d.phoneMasked || null;
                accountInfo.hint = d.hint || '';
                if (accountInfo.phoneBound && !bindForm.phone) {
                    bindForm.phone = d.phone || '';
                }
            } catch (_) { /* 安全摘要失败不挡主流程 */ }
        }

        async function bindPhone() {
            const phone = (bindForm.phone || '').trim();
            const code = (bindForm.code || '').trim();
            if (!isPhoneAccount(phone)) {
                showToast('请输入 11 位手机号', 'error');
                return;
            }
            if (!code) {
                showToast('请输入验证码', 'error');
                return;
            }
            accountBusy.value = true;
            try {
                const hit = await apiJson(base + '/auth/phone/bind', {
                    method: 'POST',
                    body: JSON.stringify({ phone, code })
                });
                if (!hit.ok) {
                    showToast((hit.body && hit.body.message) || '绑定失败', 'error');
                    return;
                }
                bindForm.code = '';
                showToast('手机号已绑定，可用验证码登录 / 找回密码', 'success');
                await loadAccountInfo();
            } catch (e) {
                showToast('绑定失败：' + e.message, 'error');
            } finally {
                accountBusy.value = false;
            }
        }

        async function changePassword() {
            if (!pwdForm.oldPassword) {
                showToast('请输入当前密码', 'error');
                return;
            }
            if (!pwdForm.newPassword || pwdForm.newPassword.length < 6) {
                showToast('新密码至少 6 位', 'error');
                return;
            }
            if (pwdForm.newPassword !== pwdForm.confirmPassword) {
                showToast('两次新密码不一致', 'error');
                return;
            }
            accountBusy.value = true;
            try {
                const hit = await apiJson(base + '/auth/password/change', {
                    method: 'POST',
                    body: JSON.stringify({
                        oldPassword: pwdForm.oldPassword,
                        newPassword: pwdForm.newPassword
                    })
                });
                if (!hit.ok) {
                    showToast((hit.body && hit.body.message) || '修改失败', 'error');
                    return;
                }
                pwdForm.oldPassword = '';
                pwdForm.newPassword = '';
                pwdForm.confirmPassword = '';
                showToast('密码已更新', 'success');
            } catch (e) {
                showToast('修改失败：' + e.message, 'error');
            } finally {
                accountBusy.value = false;
            }
        }

        /** 401：亮横幅，先别清 token（清了整页变登录，对话/曲线当场蒸发） */
        function markAuthExpired(msg) {
            if (!token.value) return;
            authExpired.value = true;
            showToast(msg || '登录过期，点上方「重新登录」继续', 'error');
        }

        function confirmRelogin() {
            authExpired.value = false;
            token.value = '';
            localStorage.removeItem('gym_token');
            role.value = 0;
            stopRestTimer();
            scrubLoginForm();
        }

        /** 统一拉 JSON：401 不当「没数据」，5xx 可重试 */
        async function apiJson(url, options) {
            const res = await fetch(url, Object.assign({ headers: authHeaders() }, options || {}));
            if (res.status === 401) {
                markAuthExpired();
                return { ok: false, auth: true, status: 401, body: null };
            }
            const body = await res.json().catch(() => ({}));
            const ok = res.ok && body.code === 200;
            return { ok, auth: false, status: res.status, body };
        }

        async function login() {
            clearLoginFieldErrors();
            if (!loginForm.username.trim()) {
                loginFieldErrors.username = '请输入手机号或用户名';
                return;
            }
            if (!loginForm.password) {
                loginFieldErrors.password = '请输入密码';
                return;
            }
            loggingIn.value = true;
            loginError.value = '';
            try {
                const res = await fetch(base + '/auth/login', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        username: loginForm.username.trim(),
                        password: loginForm.password
                    })
                });
                const data = await res.json();
                if (data.code === 200 && typeof data.data === 'string') {
                    applyAuthToken(data.data, '欢迎回来，' + parseDisplayName(data.data));
                } else {
                    loginError.value = (data && data.message) ? data.message : '用户名或密码不对，再试一次';
                    console.warn('login failed', data);
                }
            } catch (e) {
                loginError.value = '网络不稳定，请再试一次';
                console.warn('login error', e);
            } finally {
                loggingIn.value = false;
            }
        }

        async function loginBySms() {
            clearLoginFieldErrors();
            const phone = (loginForm.phone || '').trim();
            if (!isPhoneAccount(phone)) {
                loginFieldErrors.phone = '请输入 11 位手机号';
                return;
            }
            if (!(loginForm.smsCode || '').trim()) {
                loginFieldErrors.smsCode = '请输入验证码';
                return;
            }
            loggingIn.value = true;
            loginError.value = '';
            try {
                const res = await fetch(base + '/auth/login/sms', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ phone, code: loginForm.smsCode.trim() })
                });
                const data = await res.json();
                if (data.code === 200 && typeof data.data === 'string') {
                    applyAuthToken(data.data, '欢迎回来，' + parseDisplayName(data.data));
                } else {
                    loginError.value = (data && data.message) ? data.message : '验证码登录失败';
                }
            } catch (e) {
                loginError.value = '网络不稳定，请再试一次';
                console.warn('sms login error', e);
            } finally {
                loggingIn.value = false;
            }
        }

        async function register() {
            clearLoginFieldErrors();
            const u = loginForm.username.trim();
            if (!u) {
                loginFieldErrors.username = '请输入手机号或用户名';
                return;
            }
            if (!/^[a-zA-Z0-9_]{3,32}$/.test(u) && !isPhoneAccount(u)) {
                loginFieldErrors.username = '用 3～32 位字母数字下划线，或 11 位手机号';
                return;
            }
            if (isPhoneAccount(u) && !(loginForm.smsCode || '').trim()) {
                loginFieldErrors.smsCode = '手机号注册请填写验证码（演示 123456）';
                return;
            }
            if (!loginForm.password || loginForm.password.length < 6) {
                loginFieldErrors.password = '密码至少 6 位';
                return;
            }
            if (loginForm.password !== loginForm.confirmPassword) {
                loginFieldErrors.confirmPassword = '两次密码不一致';
                return;
            }
            loggingIn.value = true;
            loginError.value = '';
            try {
                const payload = {
                    username: u,
                    password: loginForm.password,
                    realName: (loginForm.realName || '').trim() || null
                };
                if (isPhoneAccount(u)) {
                    payload.phone = u;
                    payload.smsCode = loginForm.smsCode.trim();
                }
                const res = await fetch(base + '/auth/register', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(payload)
                });
                const data = await res.json();
                if (data.code === 200 && typeof data.data === 'string') {
                    applyAuthToken(data.data, '注册成功，欢迎加入', { onboard: true });
                } else {
                    loginError.value = (data && data.message) ? data.message : '注册失败，换个用户名再试';
                    console.warn('register failed', data);
                }
            } catch (e) {
                loginError.value = '网络不稳定，请再试一次';
                console.warn('register error', e);
            } finally {
                loggingIn.value = false;
            }
        }

        async function resetPassword() {
            clearLoginFieldErrors();
            const phone = (loginForm.phone || '').trim();
            if (!isPhoneAccount(phone)) {
                loginFieldErrors.phone = '请输入 11 位手机号';
                return;
            }
            if (!(loginForm.smsCode || '').trim()) {
                loginFieldErrors.smsCode = '请输入验证码';
                return;
            }
            if (!loginForm.password || loginForm.password.length < 6) {
                loginFieldErrors.password = '新密码至少 6 位';
                return;
            }
            if (loginForm.password !== loginForm.confirmPassword) {
                loginFieldErrors.confirmPassword = '两次密码不一致';
                return;
            }
            loggingIn.value = true;
            loginError.value = '';
            try {
                const res = await fetch(base + '/auth/password/reset', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        phone,
                        code: loginForm.smsCode.trim(),
                        newPassword: loginForm.password
                    })
                });
                const data = await res.json();
                if (data.code === 200 && typeof data.data === 'string') {
                    applyAuthToken(data.data, '密码已重置，欢迎回来');
                } else {
                    loginError.value = (data && data.message) ? data.message : '重置失败，请检查验证码';
                }
            } catch (e) {
                loginError.value = '网络不稳定，请再试一次';
                console.warn('reset password error', e);
            } finally {
                loggingIn.value = false;
            }
        }

        function logout() {
            fetch(base + '/auth/logout', {
                method: 'POST',
                headers: authHeaders()
            }).catch(() => {});
            token.value = '';
            localStorage.removeItem('gym_token');
            role.value = 0;
            displayName.value = '铁友';
            currentUserId.value = '';
            messages.value = [];
            tab.value = 'train';
            authMode.value = 'login';
            scrubLoginForm();
            showToast('已退出登录', 'success');
        }

        function authHeaders() {
            return { 'Authorization': 'Bearer ' + token.value, 'Content-Type': 'application/json' };
        }

        // ---------- 训练打卡 ----------
        async function loadTrainPage() {
            if (!loggedIn.value) return;
            // 永不挡全页骨架：组间休息只想记一组，不要再闪 logo / 灰条
            trainBusy.value = false;
            // 先亮「加载中」，避免进页瞬间闪「还没有可选动作」
            exercisesLoading.value = true;
            // 动作库立刻拉，别等「上次同部位」——那边一慢/一挂，页面就会假空白
            exTag.value = '';
            exKeyword.value = '';
            exerciseListExpanded.value = false;
            const softJson = (url) => fetch(url, { headers: authHeaders() })
                .then(r => r.json())
                .catch(() => ({ code: 500, data: null }));
            try {
                const [lastRes, todayRes] = await Promise.all([
                    softJson(base + '/training/last-same-part'),
                    softJson(base + '/training/today'),
                    loadExercises(),
                    loadRecentExercises()
                ]);
                if (lastRes && lastRes.code === 200 && lastRes.data) {
                    lastSession.date = lastRes.data.date || '';
                    lastSession.sessionId = lastRes.data.sessionId;
                    lastSession.exercises = lastRes.data.exercises || [];
                }
                if (todayRes && todayRes.code === 200 && todayRes.data) {
                    today.sessionId = todayRes.data.sessionId;
                    today.date = todayRes.data.date || '';
                    today.sets = todayRes.data.sets || [];
                    // 组间回来要看见刚记的组，别还得自己展开
                    if (today.sets.length) todayListOpen.value = true;
                }
                // 万一并发把列表冲空，再兜一次「全部」
                if (!exerciseList.value.length) {
                    exTag.value = '';
                    exKeyword.value = '';
                    await loadExercises();
                }
            } catch (e) {
                pad.hint = '加载失败：' + e.message;
                if (!exerciseList.value.length) {
                    try { await loadExercises(); } catch (_) { /* 空态按钮还能再点 */ }
                }
            } finally {
                trainBusy.value = false;
            }
        }

        /** 近 56 天常用（含样板）→ 快捷栏；冷启动后端会回落三大项 */
        async function loadRecentExercises() {
            try {
                const res = await fetch(base + '/training/exercises/recent?days=56&limit=5', {
                    headers: authHeaders()
                });
                const data = await res.json();
                recentExercises.value = (data.code === 200 && data.data) ? data.data : [];
            } catch (e) {
                recentExercises.value = [];
            }
        }

        async function loadExercises() {
            const seq = ++exerciseLoadSeq;
            exercisesLoading.value = true;
            const tag = exTag.value;
            const kw = exKeyword.value.trim();
            const q = new URLSearchParams();
            if (tag) q.set('tag', tag);
            if (kw) q.set('keyword', kw);
            try {
                const res = await fetch(base + '/training/exercises?' + q.toString(), { headers: authHeaders() });
                const data = await res.json();
                if (seq !== exerciseLoadSeq) return; // 过期响应丢弃
                if (data.code === 200) {
                    let list = data.data || [];
                    // 带筛选结果为空时，自动回退到全部，避免死胡同
                    if (!list.length && (tag || kw)) {
                        exTag.value = '';
                        exKeyword.value = '';
                        const all = await fetch(base + '/training/exercises', { headers: authHeaders() })
                            .then(r => r.json());
                        if (seq !== exerciseLoadSeq) return;
                        list = (all.code === 200 && all.data) ? all.data : [];
                    }
                    exerciseList.value = list;
                } else {
                    exerciseList.value = [];
                    showToast(data.message || '动作库加载失败', 'error');
                }
            } catch (e) {
                if (seq !== exerciseLoadSeq) return;
                exerciseList.value = [];
                showToast('动作库加载失败：' + e.message, 'error');
            } finally {
                if (seq === exerciseLoadSeq) exercisesLoading.value = false;
            }
        }

        function setExTag(t) { exTag.value = t; loadExercises(); }

        /** 雪花 ID 一律当字符串，禁止 Number()——超过 2^53 会丢精度 */
        function snowflake(id) {
            if (id == null || id === '') return null;
            return String(id);
        }

        function resetPadMeta() {
            pad.rpe = null;
            pad.warmup = false;
            pad.cueOpen = false;
            pad.cue = null;
            pad.suggest = null;
        }

        async function loadSuggestWeight(exerciseId) {
            if (!exerciseId) return;
            try {
                const hit = await apiJson(base + '/training/suggest-weight?exerciseId='
                    + encodeURIComponent(exerciseId) + '&targetReps=5');
                if (hit.ok && hit.body && hit.body.data) {
                    pad.suggest = hit.body.data;
                    if (pad.suggest.available && pad.suggest.suggestedWeightKg != null
                            && (pad.weight === '' || pad.weight == null)) {
                        pad.weight = String(pad.suggest.suggestedWeightKg);
                    }
                }
            } catch (_) { /* 建议失败不挡记组 */ }
        }

        function applySuggestWeight() {
            if (!pad.suggest || pad.suggest.suggestedWeightKg == null) return;
            pad.weight = String(pad.suggest.suggestedWeightKg);
            if (pad.suggest.targetReps) pad.reps = String(pad.suggest.targetReps);
            pad.hint = '已填入建议重量 · 确认后点保存';
            onPadChange();
        }

        function askWhySuggest() {
            if (!pad.suggest || !pad.suggest.reason) return;
            const q = (pad.name || '这个动作') + '为什么建议 '
                + (pad.suggest.suggestedWeightKg || '') + 'kg？' + pad.suggest.reason;
            tab.value = 'chat';
            question.value = q;
            nextTick(() => send());
        }

        async function loadExerciseCue(exerciseId) {
            if (!exerciseId) return;
            try {
                const hit = await apiJson(base + '/training/exercises/' + encodeURIComponent(exerciseId) + '/cues');
                if (hit.ok && hit.body && hit.body.data) {
                    const c = hit.body.data;
                    const has = !!(c.steps || c.commonMistakes || c.breathing || c.safetyTips);
                    pad.cue = has ? c : null;
                } else {
                    pad.cue = null;
                }
            } catch (_) {
                pad.cue = null;
            }
        }

        function againSet(ex) {
            pad.open = true;
            pad.exerciseId = snowflake(ex.exerciseId);
            pad.name = ex.name;
            pad.lastWeightKg = ex.lastWeightKg;
            pad.lastReps = ex.lastReps;
            pad.weight = String(ex.lastWeightKg);
            pad.reps = String(ex.lastReps);
            pad.setId = null;
            resetPadMeta();
            pad.hint = '已填入上次 ' + ex.lastWeightKg + 'kg × ' + ex.lastReps + ' · 确认后点保存';
            loadExerciseCue(pad.exerciseId);
            loadSuggestWeight(pad.exerciseId);
        }

        function openCustomExercise() {
            customEx.open = true;
            if (!customEx.name && exKeyword.value) {
                customEx.name = exKeyword.value.trim();
            }
            nextTick(() => {
                const el = document.querySelector('.custom-ex-card input');
                if (el) el.focus();
            });
        }

        async function saveCustomExercise() {
            const name = (customEx.name || '').trim();
            if (!name) {
                showToast('先填动作名', 'error');
                return;
            }
            customExBusy.value = true;
            try {
                const hit = await apiJson(base + '/training/exercises', {
                    method: 'POST',
                    body: JSON.stringify({
                        name,
                        equipment: customEx.equipment || 1,
                        primaryMuscle: customEx.primaryMuscle || '其他',
                        alias: '自定义'
                    })
                });
                if (!hit.ok || !hit.body || !hit.body.data) {
                    showToast((hit.body && hit.body.message) || '创建失败', 'error');
                    return;
                }
                const ex = hit.body.data;
                customEx.open = false;
                customEx.name = '';
                // 插到列表最前，方便再点
                exerciseList.value = [ex].concat(
                    (exerciseList.value || []).filter(e => String(e.id) !== String(ex.id))
                );
                exKeyword.value = ex.name || name;
                showToast('已加入动作库，开始记组', 'success');
                pickExercise(ex);
            } catch (e) {
                showToast('创建失败：' + e.message, 'error');
            } finally {
                customExBusy.value = false;
            }
        }

        function pickExercise(ex) {
            // 第二步：选完动作弹出底部记组弹层
            pad.open = true;
            pad.exerciseId = snowflake(ex.id);
            pad.name = ex.name;
            pad.setId = null;
            resetPadMeta();
            loadExerciseCue(pad.exerciseId);
            loadSuggestWeight(pad.exerciseId);
            const prev = lastSession.exercises.find(e => String(e.exerciseId) === String(ex.id));
            // 今日同动作最后一组也可当「上次」
            const todayHit = [...today.sets].reverse().find(s => String(s.exerciseId) === String(ex.id));
            if (prev) {
                pad.lastWeightKg = prev.lastWeightKg;
                pad.lastReps = prev.lastReps;
                pad.weight = '';
                pad.reps = '';
                pad.hint = '点「上次重量」可一键填入，或直接输入';
            } else if (todayHit) {
                pad.lastWeightKg = todayHit.weightKg;
                pad.lastReps = todayHit.reps;
                pad.weight = '';
                pad.reps = '';
                pad.hint = '点「上次重量」可一键填入，或直接输入';
            } else {
                pad.lastWeightKg = null;
                pad.lastReps = null;
                pad.weight = '';
                pad.reps = '';
                pad.hint = '填写重量和次数后点保存';
            }
        }

        /** 关闭记组弹层，回到选动作 */
        function closePad() {
            if (padTimer) { clearTimeout(padTimer); padTimer = null; }
            pad.open = false;
            pad.exerciseId = null;
            pad.lastWeightKg = null;
            pad.lastReps = null;
            resetPadMeta();
            pad.hint = '填写重量和次数后保存';
        }

        function setPadRpe(v) {
            pad.rpe = (pad.rpe === v) ? null : v;
        }

        function toggleWarmup() {
            pad.warmup = !pad.warmup;
        }

        function formatRest(sec) {
            const s = Math.max(0, sec | 0);
            const m = Math.floor(s / 60);
            const r = s % 60;
            return m + ':' + String(r).padStart(2, '0');
        }

        function stopRestTimer() {
            if (restTimer) { clearInterval(restTimer); restTimer = null; }
            rest.active = false;
            rest.leftSec = 0;
            rest.label = '';
        }

        function startRestTimer(sec, label) {
            stopRestTimer();
            const total = Math.max(15, parseInt(sec, 10) || 90);
            restPrefSec.value = total;
            restCustomSec.value = String(total);
            try { localStorage.setItem('gym_rest_sec', String(total)); } catch (_) {}
            rest.active = true;
            rest.totalSec = total;
            rest.leftSec = total;
            rest.label = label || '组间休息';
            restTimer = setInterval(() => {
                rest.leftSec -= 1;
                if (rest.leftSec <= 0) {
                    stopRestTimer();
                    showToast('休息结束，上下一组', 'success');
                    if (typeof navigator !== 'undefined' && navigator.vibrate) {
                        try { navigator.vibrate(80); } catch (_) {}
                    }
                }
            }, 1000);
        }

        function startCustomRest() {
            startRestTimer(restCustomSec.value || restPrefSec.value || 90, '组间休息');
        }

        function bumpRest(delta) {
            if (!rest.active) return;
            rest.leftSec = Math.max(5, rest.leftSec + delta);
            rest.totalSec = Math.max(rest.totalSec, rest.leftSec);
        }

        /** 一键填入上次同动作重量×次数 */
        function applyLastWeight() {
            if (pad.lastWeightKg == null) return;
            pad.weight = String(pad.lastWeightKg);
            pad.reps = String(pad.lastReps == null ? 5 : pad.lastReps);
            pad.hint = '已填入上次 ' + pad.weight + 'kg × ' + pad.reps + ' · 确认后点保存';
        }

        function toggleSetDetail(setId) {
            const id = String(setId);
            expandedSetId.value = expandedSetId.value === id ? null : id;
        }

        function startCheckIn() {
            tab.value = 'train';
            nextTick(() => {
                const el = document.getElementById('exercise-library');
                if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' });
            });
        }

        /** 「我的」空状态：去打卡 */
        function goTrain() {
            tab.value = 'train';
            startCheckIn();
        }

        function goMe() {
            tab.value = 'me';
            meSub.value = 'mine';
            loadMyPage();
        }

        function goForum() {
            tab.value = 'forum';
            if (forumView.value === 'detail' || forumView.value === 'compose') {
                /* keep */
            } else {
                forumView.value = 'list';
            }
            if (forumView.value === 'list' || forumView.value === 'mine' || forumView.value === 'fav') {
                loadForumList();
            }
        }

        function forumCatText(c) {
            return ({ 1: '增肌', 2: '减脂', 3: '饮食', 4: '闲聊', 5: '求助' })[c] || '其他';
        }

        function switchForumView(v) {
            forumView.value = v;
            forumDetail.value = null;
            loadForumList();
        }

        function backForumList() {
            forumView.value = 'list';
            forumDetail.value = null;
            forumReplyTo.value = null;
            loadForumList();
        }

        async function loadForumList() {
            forumBusy.value = true;
            try {
                let url;
                if (forumView.value === 'mine') {
                    url = base + '/forum/posts/mine?page=1&size=30';
                } else if (forumView.value === 'fav') {
                    url = base + '/forum/favorites?page=1&size=30';
                } else {
                    const q = new URLSearchParams({ page: '1', size: '30', sort: forumSort.value });
                    if (forumCategory.value > 0) q.set('category', String(forumCategory.value));
                    url = base + '/forum/posts?' + q.toString();
                }
                const r = await apiJson(url);
                if (r.ok) forumPosts.value = (r.body.data && r.body.data.records) || [];
                else showToast((r.body && r.body.message) || '加载失败', 'error');
            } finally {
                forumBusy.value = false;
            }
        }

        async function openForumPost(id) {
            forumBusy.value = true;
            try {
                const r = await apiJson(base + '/forum/posts/' + id);
                if (!r.ok) {
                    showToast((r.body && r.body.message) || '加载失败', 'error');
                    return;
                }
                forumDetail.value = r.body.data;
                forumView.value = 'detail';
                forumCommentText.value = '';
                forumReplyTo.value = null;
            } finally {
                forumBusy.value = false;
            }
        }

        async function submitForumPost() {
            if (!forumForm.title.trim() || !forumForm.content.trim()) {
                showToast('请填写标题和正文', 'error');
                return;
            }
            forumBusy.value = true;
            try {
                const r = await apiJson(base + '/forum/posts', {
                    method: 'POST',
                    headers: Object.assign({ 'Content-Type': 'application/json' }, authHeaders()),
                    body: JSON.stringify({
                        title: forumForm.title.trim(),
                        content: forumForm.content.trim(),
                        category: forumForm.category
                    })
                });
                if (!r.ok) {
                    showToast((r.body && r.body.message) || '发布失败', 'error');
                    return;
                }
                const p = r.body.data || {};
                if (p.status === 1) showToast('已发布（AI 审核通过）', 'success');
                else if (p.status === 2) showToast('未通过：' + (p.aiAuditMsg || '审核拒绝'), 'error');
                else showToast('已提交', 'success');
                forumForm.title = '';
                forumForm.content = '';
                forumForm.category = 4;
                forumView.value = 'mine';
                await loadForumList();
            } finally {
                forumBusy.value = false;
            }
        }

        async function submitForumComment() {
            if (!forumDetail.value || !forumCommentText.value.trim()) return;
            forumBusy.value = true;
            try {
                const r = await apiJson(base + '/forum/comments', {
                    method: 'POST',
                    headers: Object.assign({ 'Content-Type': 'application/json' }, authHeaders()),
                    body: JSON.stringify({
                        postId: forumDetail.value.id,
                        parentId: forumReplyTo.value || 0,
                        content: forumCommentText.value.trim()
                    })
                });
                if (!r.ok) {
                    showToast((r.body && r.body.message) || '评论失败', 'error');
                    return;
                }
                forumCommentText.value = '';
                forumReplyTo.value = null;
                await openForumPost(forumDetail.value.id);
            } finally {
                forumBusy.value = false;
            }
        }

        async function toggleForumLike(targetType, targetId) {
            const r = await apiJson(base + '/forum/likes', {
                method: 'POST',
                headers: Object.assign({ 'Content-Type': 'application/json' }, authHeaders()),
                body: JSON.stringify({ targetType, targetId })
            });
            if (!r.ok) {
                showToast((r.body && r.body.message) || '操作失败', 'error');
                return;
            }
            if (forumDetail.value) await openForumPost(forumDetail.value.id);
        }

        async function toggleForumFav(postId) {
            const r = await apiJson(base + '/forum/favorites?postId=' + postId, { method: 'POST' });
            if (!r.ok) {
                showToast((r.body && r.body.message) || '操作失败', 'error');
                return;
            }
            if (forumDetail.value) await openForumPost(forumDetail.value.id);
            showToast(r.body.data && r.body.data.favorited ? '已收藏' : '已取消收藏', 'success');
        }

        async function deleteForumPost(id) {
            if (!confirm('确认删除该帖？')) return;
            const r = await apiJson(base + '/forum/posts/' + id, { method: 'DELETE' });
            if (!r.ok) {
                showToast((r.body && r.body.message) || '删除失败', 'error');
                return;
            }
            showToast('已删除', 'success');
            backForumList();
        }

        async function loadAdminForum() {
            adminForumBusy.value = true;
            try {
                const r = await apiJson(base + '/api/forum/posts?page=1&size=50&status=' + adminForumStatus.value);
                if (r.ok) adminForumPosts.value = (r.body.data && r.body.data.records) || [];
                else showToast((r.body && r.body.message) || '加载失败', 'error');
            } finally {
                adminForumBusy.value = false;
            }
        }

        async function auditForumPost(id, action) {
            const path = action === 'pass' ? '/pass' : '/reject';
            const r = await apiJson(base + '/api/forum/posts/' + id + path, { method: 'POST' });
            if (!r.ok) {
                showToast((r.body && r.body.message) || '操作失败', 'error');
                return;
            }
            showToast(action === 'pass' ? '已通过' : '已拒绝', 'success');
            loadAdminForum();
        }

        async function adminDeleteForum(id) {
            if (!confirm('确认删除？')) return;
            const r = await apiJson(base + '/api/forum/posts/' + id, { method: 'DELETE' });
            if (!r.ok) {
                showToast((r.body && r.body.message) || '删除失败', 'error');
                return;
            }
            loadAdminForum();
        }

        function goGym() {
            if (!isAdmin.value) {
                showToast('场馆后台仅管理员可进', 'error');
                tab.value = 'train';
                return;
            }
            tab.value = 'gym';
            meSub.value = 'mine';
            if (!gymLoaded.value) loadGym();
            runHealthCheck();
        }

        function onPadChange() {
            // 调重量时绝不偷偷入库——手感发飘就是这么来的
            if (padTimer) { clearTimeout(padTimer); padTimer = null; }
            if (!pad.exerciseId || padSaving.value) return;
            const hasW = pad.weight !== '' && pad.weight != null;
            const hasR = pad.reps !== '' && pad.reps != null;
            pad.hint = (hasW && hasR) ? '调稳后点「保存本组」' : '先填重量和次数';
        }

        function bump(field, delta) {
            if (!pad.exerciseId) return;
            if (field === 'weight') {
                const base = pad.weight === '' || pad.weight == null ? 0 : (parseFloat(pad.weight) || 0);
                pad.weight = String(Math.max(0, Math.round((base + delta) * 10) / 10));
            } else {
                const base = pad.reps === '' || pad.reps == null ? 0 : (parseInt(pad.reps, 10) || 0);
                pad.reps = String(Math.max(1, base + delta));
            }
            onPadChange();
        }

        function nextSet() {
            pad.setId = null;
            // 下一组默认正式组；热身要自己再点
            pad.warmup = false;
            pad.hint = '下一组——调好数字后点「保存本组」';
        }

        function scheduleSave() {
            // 保留空壳，避免旧调用报错；不再自动保存
            if (padTimer) { clearTimeout(padTimer); padTimer = null; }
        }

        async function confirmPad() {
            if (padTimer) { clearTimeout(padTimer); padTimer = null; }
            if (!pad.exerciseId || padSaving.value) return;
            padFieldErrors.weight = '';
            padFieldErrors.reps = '';
            const weight = parseFloat(pad.weight);
            const reps = parseInt(pad.reps, 10);
            let bad = false;
            if (!(weight >= 0) || pad.weight === '' || Number.isNaN(weight)) {
                padFieldErrors.weight = '请填写重量';
                bad = true;
            }
            if (!(reps > 0) || Number.isNaN(reps)) {
                padFieldErrors.reps = '请填写次数';
                bad = true;
            }
            if (bad) {
                pad.hint = '请把重量和次数填完整';
                return;
            }
            if (weight >= 100 && injuryWarn.value) {
                showToast('建议先降重量再硬顶', 'error');
            }
            // 演示锁定：demo 账号在卧推/肩推上猛加重量会冲掉「平台期」剧本
            const uname = (parseJwt(token.value).username || '').toLowerCase();
            const storyLift = /卧推|肩推|推举/.test(pad.name || '');
            if (demoStoryLock.value && uname === 'demo' && storyLift && !pad.warmup) {
                showToast('演示硬锁：demo 正式组不能改写卧推/肩推平台期（可记热身，或换账号）', 'error');
                pad.hint = '已拦截——服务端也会拒绝这组正式记录';
                return;
            }
            padSaving.value = true;
            pad.hint = '保存中…';
            try {
                const body = {
                    exerciseId: String(pad.exerciseId),
                    weightKg: weight,
                    reps: reps,
                    warmup: !!pad.warmup
                };
                if (pad.rpe != null) body.rpe = pad.rpe;
                if (pad.setId != null && pad.setId !== '') {
                    body.setId = String(pad.setId);
                }
                const res = await fetch(base + '/training/sets', {
                    method: 'POST', headers: authHeaders(),
                    body: JSON.stringify(body)
                });
                if (res.status === 401) {
                    markAuthExpired('登录过期，这组没存上——重新登录后再记');
                    pad.hint = '登录过期，请重新登录后再保存';
                    return;
                }
                const data = await res.json().catch(() => ({}));
                if (!res.ok || data.code !== 200) {
                    console.warn('save set failed', res.status, data);
                    if (res.status === 403) {
                        pad.hint = '你没有权限访问这个功能';
                    } else {
                        pad.hint = '保存失败：' + ((data && data.message) || '请再试一次');
                    }
                    showToast(pad.hint, 'error');
                    return;
                }
                pad.setId = snowflake(data.data.setId);
                pad.lastWeightKg = weight;
                pad.lastReps = reps;
                const kind = pad.warmup ? '热身' : '正式';
                pad.hint = '已保存 ✓ ' + kind + '第' + data.data.setIndex + '组'
                    + (pad.rpe != null ? (' · RPE ' + pad.rpe) : '');
                await refreshToday();
                todayListOpen.value = true;
                hasRealTrainingData.value = true;
                showToast(pad.warmup ? '热身组已记' : '已记录一组', 'success');
                // 正式组 90s / 热身 60s——组间不用切出计时 App
                const pref = pad.warmup ? 60 : (restPrefSec.value || 90);
                startRestTimer(pref, pad.warmup ? '热身休息' : '组间休息');
            } catch (e) {
                console.warn('save set error', e);
                // 弱网：进本地队列，有网再刷
                enqueueOfflineSet({
                    exerciseId: String(pad.exerciseId),
                    weightKg: weight,
                    reps: reps,
                    warmup: !!pad.warmup,
                    rpe: pad.rpe,
                    name: pad.name,
                    clientRequestId: 'local-' + Date.now() + '-' + Math.random().toString(36).slice(2, 8)
                });
                pad.hint = '网络不稳，已先记在本地，联网后自动同步';
                showToast(pad.hint, 'error');
            } finally {
                padSaving.value = false;
            }
        }

        function persistOfflineQueue() {
            try { localStorage.setItem('gym_offline_sets', JSON.stringify(offlineQueue.value || [])); } catch (_) {}
        }

        function enqueueOfflineSet(item) {
            const q = (offlineQueue.value || []).slice();
            q.push(item);
            offlineQueue.value = q;
            persistOfflineQueue();
        }

        async function flushOfflineQueue() {
            const q = (offlineQueue.value || []).slice();
            if (!q.length || !loggedIn.value) return;
            const remain = [];
            for (const item of q) {
                try {
                    const body = {
                        exerciseId: String(item.exerciseId),
                        weightKg: item.weightKg,
                        reps: item.reps,
                        warmup: !!item.warmup
                    };
                    if (item.rpe != null) body.rpe = item.rpe;
                    const res = await fetch(base + '/training/sets', {
                        method: 'POST', headers: authHeaders(),
                        body: JSON.stringify(body)
                    });
                    if (!res.ok) { remain.push(item); continue; }
                    const data = await res.json().catch(() => ({}));
                    if (data.code !== 200) remain.push(item);
                } catch (_) {
                    remain.push(item);
                }
            }
            offlineQueue.value = remain;
            persistOfflineQueue();
            if (q.length && remain.length < q.length) {
                showToast('已同步 ' + (q.length - remain.length) + ' 组离线记录', 'success');
                await refreshToday();
            }
        }

        async function refreshToday() {
            const res = await fetch(base + '/training/today', { headers: authHeaders() });
            if (!res.ok) return;
            const data = await res.json();
            if (data.code === 200 && data.data) {
                today.sessionId = data.data.sessionId;
                today.sets = data.data.sets || [];
            }
        }

        async function reportDeviation(type) {
            try {
                if (!today.sessionId) {
                    const ens = await fetch(base + '/training/today/ensure', {
                        method: 'POST', headers: authHeaders()
                    }).then(r => r.json());
                    if (ens.code === 200) today.sessionId = ens.data.sessionId;
                }
                if (type === 'BAD_FEEL' || type === 'INJURY_HEAVY') {
                    injuryWarn.value = '你标记了不适/状态差：大重量请先降到平时的 60%–70%';
                }
                const res = await fetch(base + '/training/deviation', {
                    method: 'POST', headers: authHeaders(),
                    body: JSON.stringify({
                        // sessionId / exerciseId 保持字符串，防雪花丢精度
                        sessionId: snowflake(today.sessionId),
                        exerciseId: snowflake(pad.exerciseId),
                        type: type,
                        note: type
                    })
                }).then(r => r.json());
                if (res.code === 200) {
                    const labels = { SKIP_SET: '减一组', SWAP: '换动作', BAD_FEEL: '状态差' };
                    devMsg.value = '已记录偏差「' + (labels[type] || type) + '」，下次生成计划会自动调强度';
                    showToast('偏差已记录', 'success');
                } else {
                    devMsg.value = '记录失败：' + (res.message || '');
                }
            } catch (e) {
                devMsg.value = '记录失败：' + e.message;
            }
        }

        async function triggerRemind() {
            try {
                const res = await fetch(base + '/training/coach/remind-now', {
                    method: 'POST', headers: authHeaders()
                }).then(r => r.json());
                if (res.code === 200) {
                    devMsg.value = '教练提醒已执行，产出 ' + res.data.reminderCount
                        + ' 条（详见应用日志 [教练提醒]）';
                    showToast('提醒任务已跑完', 'success');
                } else {
                    devMsg.value = '触发失败：' + (res.message || '');
                }
            } catch (e) {
                devMsg.value = '触发失败：' + e.message;
            }
        }

        /**
         * 「我的」页：有训练点就画曲线。
         * 判定必须和打卡 / AI 同源——今天有组或曲线有点，就绝不能再说「还没有训练记录」。
         */
        async function loadMyPage() {
            if (!meSub.value) meSub.value = 'mine';
            await Promise.all([loadMyCharts(), loadDietAndBody(), loadProfile(), loadPortalExtras(), loadAccountInfo()]);
            refreshNextSessionTip();
            if (meSub.value === 'venue') await loadVenue();
        }

        function switchMeSub(sub) {
            meSub.value = sub;
            if (sub === 'venue') loadVenue();
            if (sub === 'plan' || sub === 'mine') loadPortalExtras();
            if (sub === 'history') loadHistory();
            if (sub === 'mine') loadHabit();
        }

        async function loadProfile() {
            if (!loggedIn.value) return;
            try {
                const hit = await apiJson(base + '/training/profile');
                if (!hit.ok || !hit.body || !hit.body.data) return;
                const p = hit.body.data;
                profile.value = {
                    displayName: p.displayName || '',
                    heightCm: p.heightCm,
                    weightKg: p.weightKg,
                    targetWeightKg: p.targetWeightKg,
                    goal: p.goal || 3,
                    trainLevel: p.trainLevel || 1,
                    injuryNote: p.injuryNote || '',
                    trainingMonths: p.trainingMonths,
                    trainingYears: p.trainingYears,
                    joinDate: p.joinDate,
                    calorieGoal: p.calorieGoal,
                    proteinGoalG: p.proteinGoalG
                };
                profileForm.displayName = profile.value.displayName;
                profileForm.heightCm = p.heightCm != null ? String(p.heightCm) : '';
                profileForm.goal = profile.value.goal;
                profileForm.trainLevel = profile.value.trainLevel;
                profileForm.injuryNote = profile.value.injuryNote || '';
                profileForm.targetWeightKg = p.targetWeightKg != null ? String(p.targetWeightKg) : '';
                profileForm.trainingYears = p.trainingYears != null ? String(p.trainingYears) : '';
                profileForm.calorieGoal = p.calorieGoal != null ? String(p.calorieGoal) : '2200';
                profileForm.proteinGoalG = p.proteinGoalG != null ? String(p.proteinGoalG) : '160';
            } catch (_) { /* 档案失败不挡主流程 */ }
        }

        async function saveProfile() {
            profileBusy.value = true;
            try {
                const body = {
                    displayName: (profileForm.displayName || '').trim() || undefined,
                    goal: profileForm.goal,
                    trainLevel: profileForm.trainLevel,
                    injuryNote: profileForm.injuryNote || ''
                };
                const h = parseFloat(profileForm.heightCm);
                if (!Number.isNaN(h) && profileForm.heightCm !== '') body.heightCm = h;
                const tw = parseFloat(profileForm.targetWeightKg);
                if (!Number.isNaN(tw) && profileForm.targetWeightKg !== '') body.targetWeightKg = tw;
                const ty = parseFloat(profileForm.trainingYears);
                if (!Number.isNaN(ty) && profileForm.trainingYears !== '') body.trainingYears = ty;
                const cg = parseInt(profileForm.calorieGoal, 10);
                if (!Number.isNaN(cg)) body.calorieGoal = cg;
                const pg = parseInt(profileForm.proteinGoalG, 10);
                if (!Number.isNaN(pg)) body.proteinGoalG = pg;
                const hit = await apiJson(base + '/training/profile', {
                    method: 'PUT',
                    body: JSON.stringify(body)
                });
                if (!hit.ok) {
                    showToast((hit.body && hit.body.message) || '档案保存失败', 'error');
                    return;
                }
                showToast('档案已更新', 'success');
                await loadProfile();
            } catch (e) {
                showToast('档案保存失败：' + e.message, 'error');
            } finally {
                profileBusy.value = false;
            }
        }

        /** 复用后端 Mifflin 公式；apply=true 写回 calorieGoal / proteinGoalG */
        async function suggestNutrition(apply, overrides) {
            nutriBusy.value = true;
            try {
                // 先把档案里的身高/目标存上，避免只算不落
                if (apply) {
                    const pre = {
                        goal: profileForm.goal,
                        trainLevel: profileForm.trainLevel
                    };
                    const h0 = parseFloat(profileForm.heightCm);
                    if (!Number.isNaN(h0) && profileForm.heightCm !== '') pre.heightCm = h0;
                    const tw0 = parseFloat(profileForm.targetWeightKg);
                    if (!Number.isNaN(tw0) && profileForm.targetWeightKg !== '') pre.targetWeightKg = tw0;
                    await apiJson(base + '/training/profile', {
                        method: 'PUT',
                        body: JSON.stringify(pre)
                    });
                }
                const payload = {
                    apply: !!apply,
                    sex: profileForm.sex || 'male',
                    activityLevel: profileForm.activityLevel || 'moderate',
                    goal: profileForm.goal
                };
                const age = parseInt(profileForm.age, 10);
                if (!Number.isNaN(age) && age > 0) payload.age = age;
                const h = parseFloat(profileForm.heightCm);
                if (!Number.isNaN(h) && profileForm.heightCm !== '') payload.heightCm = h;
                const wBody = bodySnap.value && bodySnap.value.available ? bodySnap.value.weightKg : null;
                const wForm = parseFloat(bodyForm.weightKg);
                if (!Number.isNaN(wForm) && bodyForm.weightKg !== '') payload.weightKg = wForm;
                else if (wBody != null) payload.weightKg = wBody;
                if (overrides) {
                    if (overrides.heightCm != null && !Number.isNaN(overrides.heightCm)) payload.heightCm = overrides.heightCm;
                    if (overrides.weightKg != null && !Number.isNaN(overrides.weightKg)) payload.weightKg = overrides.weightKg;
                    if (overrides.age != null && !Number.isNaN(overrides.age)) payload.age = overrides.age;
                    if (overrides.goal != null) payload.goal = overrides.goal;
                }
                const hit = await apiJson(base + '/training/profile/nutrition-suggest', {
                    method: 'POST',
                    body: JSON.stringify(payload)
                });
                if (!hit.ok || !hit.body || !hit.body.data) {
                    showToast((hit.body && hit.body.message) || '计算失败：请先填身高并记体重', 'error');
                    return null;
                }
                const d = hit.body.data;
                nutriSuggest.value = d;
                if (apply && d.applied) {
                    profileForm.calorieGoal = String(d.calorieGoal);
                    profileForm.proteinGoalG = String(d.proteinGoalG);
                    await loadProfile();
                    showToast('已写入 ' + d.calorieGoal + ' kcal / 蛋白 ' + d.proteinGoalG + 'g', 'success');
                } else {
                    showToast('预览：' + d.calorieGoal + ' kcal · 蛋白 ' + d.proteinGoalG + 'g（未写入）', 'success');
                }
                return d;
            } catch (e) {
                showToast('计算失败：' + e.message, 'error');
                return null;
            } finally {
                nutriBusy.value = false;
            }
        }

        async function loadPortalExtras() {
            if (!loggedIn.value) return;
            try {
                const [wHit, rHit, iHit, dHit, hHit] = await Promise.all([
                    apiJson(base + '/training/portal/week-plan'),
                    apiJson(base + '/training/portal/reminders'),
                    apiJson(base + '/training/deviation/hint'),
                    apiJson(base + '/training/diet/history?days=7'),
                    apiJson(base + '/training/habit')
                ]);
                if (wHit.ok && wHit.body && wHit.body.data) {
                    weekPlan.value = wHit.body.data;
                    if (wHit.body.data.done) planDone.value = Object.assign({}, wHit.body.data.done);
                }
                if (rHit.ok && rHit.body && Array.isArray(rHit.body.data)) reminders.value = rHit.body.data;
                if (iHit.ok && iHit.body && iHit.body.data) intensityHint.value = iHit.body.data;
                if (dHit.ok && dHit.body && Array.isArray(dHit.body.data)) dietHistory.value = dHit.body.data;
                if (hHit.ok && hHit.body && hHit.body.data) habitStats.value = hHit.body.data;
            } catch (_) { /* 扩展块失败不挡主看板 */ }
        }

        async function loadHabit() {
            try {
                const hit = await apiJson(base + '/training/habit');
                if (hit.ok && hit.body && hit.body.data) habitStats.value = hit.body.data;
            } catch (_) {}
        }

        async function loadHistory() {
            if (!loggedIn.value) return;
            historyBusy.value = true;
            try {
                const hit = await apiJson(base + '/training/history?limit=40');
                historyList.value = (hit.ok && hit.body && hit.body.data) ? hit.body.data : [];
            } catch (_) {
                historyList.value = [];
            } finally {
                historyBusy.value = false;
            }
        }

        async function toggleHistory(h) {
            const id = String(h.sessionId);
            if (historyOpenId.value === id) {
                historyOpenId.value = '';
                historyDetail.value = null;
                return;
            }
            historyOpenId.value = id;
            await loadHistoryDetail(h);
        }

        async function loadHistoryDetail(h) {
            historyBusy.value = true;
            try {
                const hit = await apiJson(base + '/training/sessions/' + encodeURIComponent(h.sessionId));
                historyDetail.value = (hit.ok && hit.body && hit.body.data) ? hit.body.data : null;
            } catch (_) {
                historyDetail.value = null;
            } finally {
                historyBusy.value = false;
            }
        }

        async function reuseHistorySession(h) {
            historyBusy.value = true;
            try {
                const hit = await apiJson(base + '/training/sessions/' + encodeURIComponent(h.sessionId) + '/reuse');
                if (!hit.ok || !hit.body || !hit.body.data || !hit.body.data.length) {
                    showToast((hit.body && hit.body.message) || '这课没有可复用的正式组', 'error');
                    return;
                }
                const list = hit.body.data;
                tab.value = 'train';
                const first = list[0];
                pad.open = true;
                pad.exerciseId = snowflake(first.exerciseId);
                pad.name = first.name;
                pad.weight = String(first.targetWeightKg);
                pad.reps = String(first.targetReps);
                pad.setId = null;
                resetPadMeta();
                pad.lastWeightKg = first.targetWeightKg;
                pad.lastReps = first.targetReps;
                pad.hint = '已带入「' + h.date + '」处方 · 共 ' + list.length + ' 个动作，先记第一项';
                loadExerciseCue(pad.exerciseId);
                loadSuggestWeight(pad.exerciseId);
                showToast('整课处方已带入，可逐个动作记组', 'success');
            } finally {
                historyBusy.value = false;
            }
        }

        async function deleteHistorySet(s) {
            const ok = await askConfirm(
                (s.exerciseName || '') + ' ' + s.weightKg + 'kg × ' + s.reps,
                '删除这组？');
            if (!ok) return;
            const hit = await apiJson(base + '/training/sets/' + encodeURIComponent(s.setId) + '/delete', { method: 'POST' });
            if (!hit.ok) {
                showToast((hit.body && hit.body.message) || '删除失败', 'error');
                return;
            }
            showToast('已删除', 'success');
            if (historyDetail.value && historyDetail.value.sessionId) {
                await loadHistoryDetail({ sessionId: historyDetail.value.sessionId });
            }
            await loadHistory();
            await refreshToday();
        }

        async function deleteTodaySet(s) {
            const ok = await askConfirm(
                (s.exerciseName || '') + ' ' + s.weightKg + 'kg × ' + s.reps,
                '删除这组？');
            if (!ok) return;
            const hit = await apiJson(base + '/training/sets/' + encodeURIComponent(s.setId) + '/delete', { method: 'POST' });
            if (!hit.ok) {
                showToast((hit.body && hit.body.message) || '删除失败', 'error');
                return;
            }
            showToast('已删除', 'success');
            await refreshToday();
        }

        async function runWeeklyReview() {
            if (reviewBusy.value) return;
            reviewBusy.value = true;
            weeklyReviewText.value = '';
            try {
                const hit = await apiJson(base + '/ai/task/review', {
                    method: 'POST',
                    body: JSON.stringify({})
                });
                if (!hit.ok || !hit.body || !hit.body.data) {
                    weeklyReviewText.value = (hit.body && hit.body.message) || '周报任务提交失败';
                    return;
                }
                const taskId = hit.body.data.taskId;
                // 轮询至完成（最长 ~90s）
                for (let i = 0; i < 45; i++) {
                    await sleep(2000);
                    const d = await apiJson(base + '/ai/task/' + encodeURIComponent(taskId));
                    if (!d.ok || !d.body || !d.body.data) continue;
                    const t = d.body.data;
                    const st = t.status;
                    if (st === 2 || st === 'SUCCESS' || String(t.statusLabel || '').indexOf('成功') >= 0) {
                        weeklyReviewText.value = formatReviewResult(t.resultJson || t.result || '');
                        return;
                    }
                    if (st === 3 || st === 'FAILED' || String(t.statusLabel || '').indexOf('失败') >= 0) {
                        weeklyReviewText.value = '周报生成失败：' + (t.errorMsg || t.message || '请稍后再试');
                        return;
                    }
                }
                weeklyReviewText.value = '周报还在生成，稍后在「我的」再点一次；任务号 ' + taskId;
            } catch (e) {
                weeklyReviewText.value = '周报失败：' + e.message;
            } finally {
                reviewBusy.value = false;
            }
        }

        function formatReviewResult(raw) {
            if (!raw) return '（空周报）';
            try {
                const obj = typeof raw === 'string' ? JSON.parse(raw) : raw;
                if (typeof obj === 'string') return obj;
                if (obj.answer) return obj.answer;
                if (obj.review) return obj.review;
                if (obj.content) return obj.content;
                if (obj.summary) return obj.summary;
                return JSON.stringify(obj, null, 2);
            } catch (_) {
                return String(raw);
            }
        }

        function askAboutReview() {
            tab.value = 'chat';
            question.value = '根据我这周的训练，下周怎么安排？周报摘要：'
                + String(weeklyReviewText.value || '').slice(0, 280);
            nextTick(() => send());
        }

        function markPlanDirty() { planDirty.value = true; }

        function addPlanExercise(d) {
            if (!d.exercises) d.exercises = [];
            d.exercises.push({ exerciseId: null, name: '', targetWeightKg: '', targetReps: 8, targetSets: 3 });
            markPlanDirty();
        }

        function removePlanExercise(d, idx) {
            if (!d.exercises) return;
            d.exercises.splice(idx, 1);
            markPlanDirty();
        }

        function toggleRestDay(d) {
            d.restDay = !d.restDay;
            markPlanDirty();
        }

        async function saveWeekPlanNow() {
            planSaving.value = true;
            try {
                const days = (weekPlan.value.days || []).map(d => ({
                    date: d.date,
                    weekday: d.weekday,
                    focus: d.focus,
                    note: d.note,
                    restDay: !!d.restDay,
                    exercises: (d.exercises || []).map(e => ({
                        exerciseId: e.exerciseId || null,
                        name: e.name,
                        targetWeightKg: e.targetWeightKg === '' || e.targetWeightKg == null ? null : Number(e.targetWeightKg),
                        targetReps: e.targetReps === '' || e.targetReps == null ? null : Number(e.targetReps),
                        targetSets: e.targetSets === '' || e.targetSets == null ? null : Number(e.targetSets)
                    }))
                }));
                const hit = await apiJson(base + '/training/portal/week-plan', {
                    method: 'POST',
                    body: JSON.stringify({ days, done: planDone.value || {} })
                });
                if (!hit.ok) {
                    showToast((hit.body && hit.body.message) || '保存失败', 'error');
                    return;
                }
                if (hit.body && hit.body.data) weekPlan.value = hit.body.data;
                planDirty.value = false;
                showToast('周计划已保存', 'success');
            } finally {
                planSaving.value = false;
            }
        }

        async function regenerateWeekPlan() {
            const ok = await askConfirm('会覆盖你改过的动作和组数。', '重新生成？');
            if (!ok) return;
            planSaving.value = true;
            try {
                const hit = await apiJson(base + '/training/portal/week-plan/regenerate', { method: 'POST' });
                if (hit.ok && hit.body && hit.body.data) {
                    weekPlan.value = hit.body.data;
                    planDone.value = {};
                    planDirty.value = false;
                    showToast('已重新生成本周计划', 'success');
                } else {
                    showToast((hit.body && hit.body.message) || '生成失败', 'error');
                }
            } finally {
                planSaving.value = false;
            }
        }

        async function loadVenue() {
            if (!loggedIn.value) return;
            venueBusy.value = true;
            venueMsg.value = '';
            try {
                const [c, b, cards, cons] = await Promise.all([
                    apiJson(base + '/training/portal/courses'),
                    apiJson(base + '/training/portal/bookings'),
                    apiJson(base + '/training/portal/cards'),
                    apiJson(base + '/training/portal/consume')
                ]);
                if (c.auth || b.auth) {
                    markAuthExpired('登录过期，场馆信息拉不到');
                    return;
                }
                venueCourses.value = (c.ok && c.body && c.body.data) || [];
                venueBookings.value = (b.ok && b.body && b.body.data) || [];
                venueCards.value = (cards.ok && cards.body && cards.body.data) || [];
                venueConsume.value = (cons.ok && cons.body && cons.body.data) || [];
            } catch (e) {
                venueMsg.value = '场馆信息加载失败：' + e.message;
            } finally {
                venueBusy.value = false;
            }
        }

        async function bookCourse(courseId) {
            venueBusy.value = true;
            try {
                const hit = await apiJson(base + '/training/portal/book?courseId=' + encodeURIComponent(courseId), {
                    method: 'POST'
                });
                if (!hit.ok) {
                    showToast((hit.body && hit.body.message) || '预约失败', 'error');
                    return;
                }
                showToast('预约成功', 'success');
                await loadVenue();
            } finally {
                venueBusy.value = false;
            }
        }

        async function cancelBooking(id) {
            venueBusy.value = true;
            try {
                const hit = await apiJson(base + '/training/portal/bookings/' + id + '/cancel', { method: 'POST' });
                if (!hit.ok) {
                    showToast((hit.body && hit.body.message) || '取消失败', 'error');
                    return;
                }
                showToast('已取消预约', 'success');
                await loadVenue();
            } finally {
                venueBusy.value = false;
            }
        }

        async function memberCheckIn() {
            venueBusy.value = true;
            try {
                const hit = await apiJson(base + '/training/portal/check-in', { method: 'POST' });
                if (!hit.ok) {
                    showToast((hit.body && hit.body.message) || '签到失败（可能没有次数卡）', 'error');
                    return;
                }
                showToast((hit.body && hit.body.data && hit.body.data.message) || '签到成功', 'success');
                await loadVenue();
            } finally {
                venueBusy.value = false;
            }
        }

        async function deleteDietItem(id) {
            const hit = await apiJson(base + '/training/diet/' + id + '/delete', { method: 'POST' });
            if (!hit.ok) {
                showToast((hit.body && hit.body.message) || '删除失败', 'error');
                return;
            }
            showToast('已删除', 'success');
            await loadDietAndBody();
            await loadPortalExtras();
        }

        async function askKnowledge() {
            const q = (knowledgeQ.value || '').trim();
            if (!q) return;
            knowledgeBusy.value = true;
            knowledgeA.value = '';
            try {
                const hit = await apiJson(base + '/knowledge/qa', {
                    method: 'POST',
                    body: JSON.stringify({ question: q })
                });
                if (!hit.ok) {
                    knowledgeA.value = (hit.body && hit.body.message)
                        || '知识库暂时不可用（多半是 ES 没起）。记组垫板里的「动作要领」仍然可用。';
                    return;
                }
                const data = hit.body && hit.body.data;
                if (!data) {
                    knowledgeA.value = '没有返回内容';
                    return;
                }
                if (data.refused) {
                    knowledgeA.value = '知识库拒答：' + (data.refuseReason || '召回不足，避免瞎编')
                        + '。可改去打卡页打开动作要领。';
                    return;
                }
                knowledgeA.value = data.answer || data.message || JSON.stringify(data);
            } catch (e) {
                knowledgeA.value = '知识库问答失败：' + e.message + '。可改用打卡页动作要领。';
            } finally {
                knowledgeBusy.value = false;
            }
        }

        function weekdayLabel(code) {
            const map = {
                MONDAY: '周一', TUESDAY: '周二', WEDNESDAY: '周三', THURSDAY: '周四',
                FRIDAY: '周五', SATURDAY: '周六', SUNDAY: '周日'
            };
            return map[code] || code || '';
        }

        function togglePlanDone(date) {
            const m = Object.assign({}, planDone.value);
            m[date] = !m[date];
            planDone.value = m;
            try { localStorage.setItem('gym_plan_done', JSON.stringify(m)); } catch (_) {}
            // 勾选同步落库（防换机丢失）
            saveWeekPlanNow();
        }

        function dismissReminder(tip) {
            const arr = (dismissedReminders.value || []).slice();
            if (!arr.includes(tip)) arr.push(tip);
            dismissedReminders.value = arr;
            try { localStorage.setItem('gym_remind_dismiss', JSON.stringify(arr)); } catch (_) {}
        }

        function saveDietAsFav() {
            const name = (dietForm.foodName || '').trim();
            const cal = parseInt(dietForm.calories, 10);
            if (!name || Number.isNaN(cal)) {
                showToast('先填好食物名和热量', 'error');
                return;
            }
            const fav = {
                name,
                cal,
                protein: parseFloat(dietForm.proteinG) || 0
            };
            const list = (dietFavs.value || []).filter(f => f.name !== name);
            list.unshift(fav);
            dietFavs.value = list.slice(0, 8);
            try { localStorage.setItem('gym_diet_favs', JSON.stringify(dietFavs.value)); } catch (_) {}
            showToast('已收藏常用餐', 'success');
        }

        /** 周计划「今日」一键带进打卡：优先用计划里的动作处方 */
        async function applyTodayPlan() {
            const days = (weekPlan.value && weekPlan.value.days) || [];
            const todayPlan = days.find(d => d.date === new Date().toISOString().slice(0, 10)) || days[0];
            if (!todayPlan) {
                showToast('还没有周计划', 'error');
                return;
            }
            if (todayPlan.restDay) {
                showToast('今天标了休息日——要练就先取消休息日', 'error');
                return;
            }
            const planned = (todayPlan.exercises || []).filter(e => e && e.name);
            tab.value = 'train';
            if (planned.length) {
                const first = planned[0];
                exKeyword.value = first.name;
                await loadExercises();
                const hit = (exerciseList.value || []).find(e => e.name === first.name)
                    || (exerciseList.value || [])[0];
                if (hit) {
                    pickExercise(hit);
                    if (first.targetWeightKg != null && first.targetWeightKg !== '') {
                        pad.weight = String(first.targetWeightKg);
                    }
                    if (first.targetReps != null && first.targetReps !== '') {
                        pad.reps = String(first.targetReps);
                    }
                    pad.hint = '已按计划带入「' + first.name + '」处方 · 今日共 '
                        + planned.length + ' 个动作';
                    showToast('已按今日计划打开打卡', 'success');
                    return;
                }
            }
            const focus = todayPlan.focus || '';
            let kw = '卧推';
            if (focus.indexOf('拉') >= 0) kw = '划船';
            else if (focus.indexOf('腿') >= 0) kw = '深蹲';
            else if (focus.indexOf('恢复') >= 0 || focus.indexOf('有氧') >= 0) kw = '卷腹';
            else if (focus.indexOf('推') >= 0) kw = '卧推';
            exKeyword.value = kw;
            await loadExercises();
            const list = exerciseList.value || [];
            if (list.length) {
                pickExercise(list[0]);
                showToast('已按今日「' + focus + '」带入动作，可再搜微调', 'success');
            } else {
                showToast('动作库空，请先点重新加载', 'error');
            }
        }

        async function runSelfCheck() {
            selfCheck.value = { items: [], running: true };
            const items = [];
            const push = (ok, title, detail) => items.push({ ok, title, detail });
            try {
                const flag = await apiJson(base + '/training/has-real-data');
                push(flag.ok, '训练数据接口', flag.ok ? '正常' : '失败');
                if (flag.auth) push(false, '登录态', '已过期——请重新登录');
                else push(true, '登录态', '有效');
                const ex = await apiJson(base + '/training/exercises?tag=');
                push(ex.ok && ex.body && (ex.body.data || []).length > 0, '动作库',
                    ex.ok ? (('共 ' + ((ex.body.data || []).length) + ' 个')) : '加载失败');
                const pl = await apiJson(base + '/training/alerts/plateau');
                push(pl.ok, '平台期提示', pl.ok ? (('条数 ' + ((pl.body.data || []).length))) : '失败');
                const wp = await apiJson(base + '/training/portal/week-plan');
                push(wp.ok, '周计划', wp.ok ? '7 日可读' : '失败');
                const kn = await apiJson(base + '/knowledge/qa', {
                    method: 'POST',
                    body: JSON.stringify({ question: '杠铃卧推常见错误' })
                });
                const kd = kn.body && kn.body.data;
                push(kn.ok && kd && (!kd.refused || (kd.answer && kd.answer.length > 0)),
                    '知识库',
                    kd && kd.refused && !(kd.answer)
                        ? ('拒答：' + (kd.refuseReason || ''))
                        : (kn.ok ? '可回答（含本地兜底）' : '失败'));
                const cards = await apiJson(base + '/training/portal/cards');
                push(cards.ok, '会员卡', cards.ok
                    ? (('张数 ' + ((cards.body.data || []).length)))
                    : '失败');
            } catch (e) {
                push(false, '自检异常', e.message);
            }
            selfCheck.value = { items, running: false };
        }

        function weightGapLine() {
            const cur = bodySnap.value && bodySnap.value.available ? bodySnap.value.weightKg : null;
            const target = profile.value && profile.value.targetWeightKg;
            if (cur == null || target == null) return '';
            const diff = Math.round((cur - target) * 10) / 10;
            if (Math.abs(diff) < 0.3) return '当前体重已接近目标 ' + target + ' kg';
            return diff > 0
                ? ('距目标体重还重 ' + diff + ' kg（目标 ' + target + '）')
                : ('距目标体重还轻 ' + Math.abs(diff) + ' kg（目标 ' + target + '）');
        }

        function dietGoalLine() {
            const cal = dietDay.value.totalCalories || 0;
            const pro = dietDay.value.totalProteinG || 0;
            const cg = profile.value.calorieGoal;
            const pg = profile.value.proteinGoalG;
            if (!cg && !pg) return '';
            const parts = [];
            if (cg) parts.push('热量 ' + cal + '/' + cg);
            if (pg) parts.push('蛋白 ' + pro + '/' + pg + 'g');
            return parts.join(' · ');
        }

        /** 新号冷启动：一键灌入半年演示剧本（含平台期/容量缺口） */
        async function seedDemoStory() {
            if (!loggedIn.value || seedBusy.value) return;
            seedBusy.value = true;
            try {
                const hit = await apiJson(base + '/demo/regenerate', { method: 'POST' });
                if (hit.auth) {
                    markAuthExpired('登录过期，演示数据没写上');
                    return;
                }
                if (!hit.ok) {
                    showToast((hit.body && hit.body.message) || '生成演示数据失败', 'error');
                    return;
                }
                const d = hit.body && hit.body.data;
                showToast('已载入演示剧本：'
                    + ((d && d.sessionCount) || '?') + ' 次训练', 'success');
                hasRealTrainingData.value = true;
                await loadMyPage();
            } catch (e) {
                showToast('生成失败：' + e.message, 'error');
            } finally {
                seedBusy.value = false;
            }
        }

        function refreshNextSessionTip() {
            const gaps = ((muscleVolume.value && muscleVolume.value.items) || [])
                .filter(it => it.verdict === 'BELOW_MEV')
                .slice(0, 2)
                .map(it => it.muscle);
            if (gaps.length) {
                nextSessionTip.value = '下一练优先补：' + gaps.join('、')
                    + '（低于 MEV）。推类若在平台期，主项用变式，别硬加重量。';
            } else if (plateauAlerts.value && plateauAlerts.value.length) {
                const a = plateauAlerts.value[0];
                nextSessionTip.value = plateauNextPlan(a);
            } else if (lastSession.exercises && lastSession.exercises.length) {
                nextSessionTip.value = '下一练可从「上次」动作一键再来；或问 AI：今天练推还是练拉？';
            } else {
                nextSessionTip.value = '还没有下一练线索——先打卡一组，或载入演示剧本看完整故事。';
            }
        }

        async function loadDietAndBody() {
            if (!loggedIn.value) return;
            try {
                const [dHit, bHit] = await Promise.all([
                    apiJson(base + '/training/diet/day'),
                    apiJson(base + '/training/body/latest')
                ]);
                if (dHit.ok && dHit.body && dHit.body.data) {
                    const d = dHit.body.data;
                    dietDay.value = {
                        date: d.date || '',
                        totalCalories: d.totalCalories || 0,
                        totalProteinG: d.totalProteinG || 0,
                        items: d.items || []
                    };
                }
                if (bHit.ok && bHit.body && bHit.body.data) {
                    bodySnap.value = bHit.body.data;
                    if (bodySnap.value.available && bodySnap.value.weightKg != null && !bodyForm.weightKg) {
                        bodyForm.weightKg = String(bodySnap.value.weightKg);
                    }
                }
            } catch (_) { /* 看板失败不挡曲线 */ }
        }

        function normalizeFood(p) {
            if (!p) return { name: '', cal: 0, protein: 0 };
            return {
                name: p.name || '',
                cal: p.cal != null ? p.cal : (p.calories != null ? p.calories : 0),
                protein: p.protein != null ? p.protein : (p.proteinG != null ? p.proteinG : 0),
                cat: p.cat || ''
            };
        }

        function applyDietPreset(p) {
            const f = normalizeFood(p);
            dietForm.foodName = f.name;
            dietForm.calories = String(f.cal);
            dietForm.proteinG = String(f.protein);
        }

        /** 点选即记：搜索结果 / 快捷芯片一键入库，组间少打字 */
        async function pickFoodAndLog(p) {
            applyDietPreset(p);
            dietSearchOpen.value = false;
            dietFoodQ.value = '';
            await saveDietQuick();
            // 自动收藏高频项（不重复堆）
            try {
                const f = normalizeFood(p);
                if (f.name) {
                    const exists = (dietFavs.value || []).some(x => x.name === f.name);
                    if (!exists) {
                        dietForm.foodName = f.name;
                        dietForm.calories = String(f.cal);
                        dietForm.proteinG = String(f.protein);
                        // 不弹 toast 二次打扰；静默写入收藏上限
                        const list = (dietFavs.value || []).slice();
                        list.unshift({ name: f.name, cal: f.cal, protein: f.protein });
                        dietFavs.value = list.slice(0, 12);
                        localStorage.setItem('gym_diet_favs', JSON.stringify(dietFavs.value));
                    }
                }
            } catch (_) {}
        }

        function dietCalPct() {
            const goal = profile.value.calorieGoal;
            if (!goal) return 0;
            return Math.min(100, Math.round((dietDay.value.totalCalories || 0) / goal * 100));
        }

        function dietProPct() {
            const goal = profile.value.proteinGoalG;
            if (!goal) return 0;
            return Math.min(100, Math.round((dietDay.value.totalProteinG || 0) / goal * 100));
        }

        async function copyYesterdayDiet() {
            if (dietBusy.value) return;
            dietBusy.value = true;
            try {
                const hit = await apiJson(base + '/training/diet/copy', { method: 'POST' });
                if (!hit.ok) {
                    showToast((hit.body && hit.body.message) || '复制失败', 'error');
                    return;
                }
                if (hit.body && hit.body.data) {
                    const d = hit.body.data;
                    dietDay.value = {
                        date: d.date || '',
                        totalCalories: d.totalCalories || 0,
                        totalProteinG: d.totalProteinG || 0,
                        items: d.items || []
                    };
                }
                showToast('已把昨天的饮食抄到今天', 'success');
                await loadPortalExtras();
            } catch (e) {
                showToast('复制失败：' + e.message, 'error');
            } finally {
                dietBusy.value = false;
            }
        }

        async function saveDietQuick() {
            const name = (dietForm.foodName || '').trim();
            const cal = parseInt(dietForm.calories, 10);
            if (!name || !(cal >= 0) || Number.isNaN(cal)) {
                showToast('食物名和热量都要填', 'error');
                return;
            }
            const qmarks = (name.match(/[?？]/g) || []).length;
            if (qmarks >= 2) {
                showToast('食物名乱码了，请重新点快捷项', 'error');
                return;
            }
            dietBusy.value = true;
            try {
                const body = {
                    foodName: name,
                    calories: cal,
                    mealType: dietForm.mealType || 4
                };
                const pg = parseFloat(dietForm.proteinG);
                if (!Number.isNaN(pg) && dietForm.proteinG !== '') body.proteinG = pg;
                const hit = await apiJson(base + '/training/diet', {
                    method: 'POST',
                    body: JSON.stringify(body)
                });
                if (!hit.ok) {
                    showToast((hit.body && hit.body.message) || '饮食保存失败', 'error');
                    return;
                }
                dietForm.foodName = '';
                dietForm.calories = '';
                dietForm.proteinG = '';
                showToast('已记一笔饮食', 'success');
                await loadDietAndBody();
            } catch (e) {
                showToast('饮食保存失败：' + e.message, 'error');
            } finally {
                dietBusy.value = false;
            }
        }

        async function saveBodyQuick() {
            const w = parseFloat(bodyForm.weightKg);
            if (!(w > 0) || Number.isNaN(w)) {
                showToast('请填写有效体重', 'error');
                return;
            }
            bodyBusy.value = true;
            try {
                const hit = await apiJson(base + '/training/body', {
                    method: 'POST',
                    body: JSON.stringify({ weightKg: w })
                });
                if (!hit.ok) {
                    showToast((hit.body && hit.body.message) || '体重保存失败', 'error');
                    return;
                }
                showToast('今日体重已更新', 'success');
                await loadDietAndBody();
            } catch (e) {
                showToast('体重保存失败：' + e.message, 'error');
            } finally {
                bodyBusy.value = false;
            }
        }

        function seriesPointCount(series) {
            if (!series) return 0;
            return Object.keys(series).reduce((n, k) => n + ((series[k] && series[k].length) || 0), 0);
        }

        async function loadMyCharts() {
            if (!loggedIn.value) return;
            busy.value = true;
            chartLoadError.value = '';
            try {
                // 一次拉齐；任一 401 不当「没数据」；失败时保留上次成功曲线
                const [flagHit, todayHit, b3Hit, volHit, mvHit, bwHit, plHit] = await Promise.all([
                    apiJson(base + '/training/has-real-data'),
                    apiJson(base + '/training/today'),
                    apiJson(base + '/training/charts/big3?weeks=26'),
                    apiJson(base + '/training/charts/weekly-volume?weeks=26'),
                    apiJson(base + '/training/charts/muscle-volume?weeks=4'),
                    apiJson(base + '/training/charts/body-weight?weeks=26'),
                    apiJson(base + '/training/alerts/plateau')
                ]);
                if (flagHit.auth || todayHit.auth || b3Hit.auth || volHit.auth || mvHit.auth
                        || bwHit.auth || plHit.auth) {
                    chartLoadError.value = '登录过期，曲线暂时拉不到——重新登录后点刷新';
                    if (lastGoodCharts.value && typeof echarts !== 'undefined') {
                        hasRealTrainingData.value = true;
                        await nextTick();
                        renderRealCharts(lastGoodCharts.value.series, lastGoodCharts.value.vols,
                            lastGoodCharts.value.notes, lastGoodCharts.value.body);
                    }
                    return;
                }

                if (todayHit.ok && todayHit.body && todayHit.body.data) {
                    today.sessionId = todayHit.body.data.sessionId;
                    today.date = todayHit.body.data.date || '';
                    today.sets = todayHit.body.data.sets || [];
                }

                if (mvHit.ok && mvHit.body && mvHit.body.data) {
                    const mv = mvHit.body.data;
                    muscleVolume.value = {
                        available: !!mv.available,
                        weeks: mv.weeks || 4,
                        items: mv.items || [],
                        reason: mv.reason || ''
                    };
                }

                plateauAlerts.value = (plHit.ok && plHit.body && Array.isArray(plHit.body.data))
                    ? plHit.body.data : [];
                bodyWeightSeries.value = (bwHit.ok && bwHit.body && Array.isArray(bwHit.body.data))
                    ? bwHit.body.data : [];

                const chartOk = b3Hit.ok && b3Hit.body && b3Hit.body.data;
                const volOk = volHit.ok;
                if (!chartOk && !volOk) {
                    // 接口挂了 ≠ 没练过：有本地今日组 / 上次成功缓存就别进「参考示意」
                    if (lastGoodCharts.value) {
                        hasRealTrainingData.value = true;
                        chartLoadError.value = '曲线暂时加载失败，正在显示上次成功的数据';
                        await nextTick();
                        if (typeof echarts !== 'undefined') {
                            renderRealCharts(lastGoodCharts.value.series, lastGoodCharts.value.vols,
                                lastGoodCharts.value.notes, lastGoodCharts.value.body);
                        }
                        return;
                    }
                    if (today.sets && today.sets.length > 0) {
                        hasRealTrainingData.value = true;
                        chartLoadError.value = '曲线加载失败，但今日组还在——点刷新重试';
                        return;
                    }
                    const apiHas = !!(flagHit.ok && flagHit.body && flagHit.body.data && flagHit.body.data.hasRealData);
                    if (apiHas) {
                        hasRealTrainingData.value = true;
                        chartLoadError.value = '有训练记录，但曲线接口失败——点刷新';
                        return;
                    }
                    hasRealTrainingData.value = false;
                    chartSparse.value = false;
                    if (typeof echarts !== 'undefined') renderDemoCharts();
                    return;
                }

                const series = (chartOk && b3Hit.body.data.series) || {};
                chartNotes.value = (chartOk && b3Hit.body.data.notes) || [];
                const vols = (volOk && Array.isArray(volHit.body.data)) ? volHit.body.data : [];
                const apiHas = !!(flagHit.ok && flagHit.body && flagHit.body.data && flagHit.body.data.hasRealData);
                const points = seriesPointCount(series);
                hasRealTrainingData.value = apiHas
                    || (today.sets && today.sets.length > 0)
                    || (lastSession.exercises && lastSession.exercises.length > 0)
                    || points > 0
                    || (vols && vols.length > 0)
                    || !!(muscleVolume.value.available && muscleVolume.value.items.length);

                const daySet = new Set();
                Object.values(series).forEach(arr => (arr || []).forEach(p => { if (p && p.x) daySet.add(p.x); }));
                chartSparse.value = hasRealTrainingData.value && daySet.size <= 1;
                const liftNames = {
                    bench: '卧推', squat: '深蹲', deadlift: '硬拉', ohp: '肩推', row: '划船'
                };
                const present = Object.keys(liftNames).filter(k => (series[k] || []).length > 0)
                    .map(k => liftNames[k]);
                const presentLabel = present.length ? present.join('、') : '今天练过的动作';
                chartSparseTip.value = chartSparse.value
                    ? ('今天是起点 · 图上只有「' + presentLabel + '」'
                        + '（已记 ' + (today.sets.length || points) + ' 组）。'
                        + '再有几天同动作记录，才会出现能看涨跌的线——不是五大项都练了。')
                    : '';

                await nextTick();
                await nextTick();

                if (!hasRealTrainingData.value) {
                    chartSparse.value = false;
                    if (typeof echarts !== 'undefined') renderDemoCharts();
                    return;
                }
                if (typeof echarts === 'undefined') {
                    showToast('图表还没加载完，请刷新后再看曲线', 'error');
                    return;
                }
                const bodyPts = bodyWeightSeries.value || [];
                lastGoodCharts.value = { series, vols, notes: chartNotes.value, body: bodyPts };
                renderRealCharts(series, vols, chartNotes.value, bodyPts);
                refreshNextSessionTip();
            } catch (e) {
                chartLoadError.value = '曲线加载失败：' + e.message;
                showToast(chartLoadError.value, 'error');
                if (lastGoodCharts.value && typeof echarts !== 'undefined') {
                    hasRealTrainingData.value = true;
                    await nextTick();
                    renderRealCharts(lastGoodCharts.value.series, lastGoodCharts.value.vols,
                        lastGoodCharts.value.notes, lastGoodCharts.value.body);
                } else if (today.sets && today.sets.length > 0) {
                    hasRealTrainingData.value = true;
                } else if (!hasRealTrainingData.value && typeof echarts !== 'undefined') {
                    renderDemoCharts();
                }
            } finally {
                busy.value = false;
            }
        }

        function volumeBarPct(item) {
            if (!item || !item.mrv) return 0;
            return Math.min(100, Math.round((item.setsPerWeek / item.mrv) * 100));
        }

        function volumeVerdictClass(v) {
            if (v === 'BELOW_MEV') return 'vol-low';
            if (v === 'MEV_TO_MAV') return 'vol-ok';
            if (v === 'MAV_TO_MRV') return 'vol-high';
            if (v === 'OVER_MRV') return 'vol-over';
            return '';
        }

        function volumeVerdictText(v) {
            if (v === 'BELOW_MEV') return '低于 MEV · 该加量';
            if (v === 'MEV_TO_MAV') return '有效区间';
            if (v === 'MAV_TO_MRV') return '接近上限';
            if (v === 'OVER_MRV') return '超量 · 考虑减载';
            return '';
        }

        function renderRealCharts(series, vols, notes, bodyPts) {
            // 固定色：有数据才进图例——别空壳陪跑
            const palette = {
                bench: { name: '卧推', color: '#d6ff00' },
                squat: { name: '深蹲', color: '#3dd68c' },
                deadlift: { name: '硬拉', color: '#ff4f1a' },
                ohp: { name: '肩推', color: '#7aa2ff' },
                row: { name: '划船', color: '#e879f9' }
            };
            const activeKeys = Object.keys(palette).filter(k => (series[k] || []).length > 0);
            const xs = Array.from(new Set(
                activeKeys.flatMap(k => (series[k] || []).map(p => p.x))
            )).sort();
            const noteList = notes || [];
            // 只铺淡色底，不写字——说明放在曲线下方，避免字砸在点上
            const markAreas = noteList.map(n => {
                const from = n.from || '';
                const to = n.to || from;
                let i0 = xs.findIndex(x => x >= from);
                let i1 = -1;
                for (let i = xs.length - 1; i >= 0; i--) {
                    if (xs[i] <= to) { i1 = i; break; }
                }
                if (i0 < 0) i0 = xs.findIndex(x => x > from);
                if (i0 < 0) return null;
                if (i1 < 0) i1 = i0;
                const soft = n.kind === 'deload' ? 'rgba(245,197,66,0.10)' : 'rgba(141,151,168,0.08)';
                return [{
                    xAxis: xs[i0],
                    itemStyle: { color: soft },
                    label: { show: false }
                }, {
                    xAxis: xs[Math.min(i1, xs.length - 1)],
                    label: { show: false }
                }];
            }).filter(Boolean);
            const sparseOneDay = xs.length <= 1;
            if (chart1El.value) {
                if (!chart1Inst) chart1Inst = echarts.init(chart1El.value);
                chart1Inst.setOption({
                    backgroundColor: 'transparent',
                    color: activeKeys.map(k => palette[k].color),
                    textStyle: { color: '#8d97a8' },
                    tooltip: {
                        trigger: 'axis',
                        formatter: function (params) {
                            if (!params || !params.length) return '';
                            let s = params[0].axisValue + '<br/>';
                            params.forEach(p => {
                                if (p.data == null) return;
                                s += p.marker + p.seriesName + ' 约能推 ' + p.data + ' kg<br/>';
                            });
                            return s + '<span style="color:#8d97a8;font-size:12px">按当天最好一组估算</span>';
                        }
                    },
                    legend: {
                        show: activeKeys.length > 0,
                        data: activeKeys.map(k => palette[k].name),
                        textStyle: { color: '#8d97a8' }
                    },
                    grid: { left: 52, right: sparseOneDay ? 72 : 20, top: 40, bottom: 32 },
                    xAxis: {
                        type: 'category',
                        data: xs.length ? xs : ['今天'],
                        axisLine: { lineStyle: { color: '#2a3340' } }
                    },
                    yAxis: {
                        type: 'value',
                        name: '估测最大力量',
                        nameTextStyle: { color: '#8d97a8', fontSize: 11 },
                        splitLine: { lineStyle: { color: '#232a35' } },
                        scale: true
                    },
                    series: activeKeys.map((k, idx) => ({
                        name: palette[k].name,
                        type: 'line',
                        smooth: !sparseOneDay,
                        connectNulls: false,
                        z: 3,
                        symbol: 'circle',
                        symbolSize: sparseOneDay ? 14 : 8,
                        itemStyle: { color: palette[k].color },
                        lineStyle: { color: palette[k].color, width: 2 },
                        label: sparseOneDay ? {
                            show: true,
                            formatter: (p) => palette[k].name + ' ' + p.data,
                            color: '#e8ecf1',
                            fontSize: 12,
                            position: 'right'
                        } : { show: false },
                        data: (xs.length ? xs : ['今天']).map(x => {
                            const hit = (series[k] || []).find(p => p.x === x);
                            return hit ? hit.y : null;
                        }),
                        markArea: idx === 0 && markAreas.length ? {
                            silent: true,
                            z: 1,
                            label: { show: false },
                            data: markAreas
                        } : undefined
                    }))
                }, true);
            }
            if (chart2El.value) {
                if (!chart2Inst) chart2Inst = echarts.init(chart2El.value);
                chart2Inst.setOption({
                    backgroundColor: 'transparent',
                    color: ['#ff7a45'],
                    textStyle: { color: '#8d97a8' },
                    tooltip: { trigger: 'axis' },
                    grid: { left: 52, right: 20, top: 30, bottom: 32 },
                    xAxis: { type: 'category', data: vols.map(r => r.week), axisLine: { lineStyle: { color: '#2a3340' } } },
                    yAxis: {
                        type: 'value',
                        name: '训练量',
                        nameTextStyle: { color: '#8d97a8', fontSize: 11 },
                        splitLine: { lineStyle: { color: '#232a35' } }
                    },
                    series: [{ name: '每周训练量', type: 'bar', data: vols.map(r => r.volumeKg), itemStyle: { borderRadius: [6, 6, 0, 0] } }]
                }, true);
            }
            const body = Array.isArray(bodyPts) ? bodyPts : (bodyWeightSeries.value || []);
            if (chart3El.value) {
                if (!chart3Inst) chart3Inst = echarts.init(chart3El.value);
                if (!body.length) {
                    chart3Inst.clear();
                } else {
                    chart3Inst.setOption({
                        backgroundColor: 'transparent',
                        color: ['#5eead4'],
                        textStyle: { color: '#8d97a8' },
                        tooltip: { trigger: 'axis' },
                        grid: { left: 52, right: 20, top: 30, bottom: 32 },
                        xAxis: {
                            type: 'category',
                            data: body.map(p => p.x),
                            axisLine: { lineStyle: { color: '#2a3340' } }
                        },
                        yAxis: {
                            type: 'value',
                            name: 'kg',
                            scale: true,
                            nameTextStyle: { color: '#8d97a8', fontSize: 11 },
                            splitLine: { lineStyle: { color: '#232a35' } }
                        },
                        series: [{
                            name: '体重',
                            type: 'line',
                            smooth: true,
                            symbol: 'circle',
                            symbolSize: 7,
                            data: body.map(p => p.y),
                            areaStyle: { color: 'rgba(94,234,212,0.12)' }
                        }]
                    }, true);
                }
            }
        }

        // ---------- P0-3：对话历史持久化（按 userId 分桶） ----------
        function persistChatHistory() {
            try {
                const key = chatHistoryKey(currentUserId.value);
                if (!currentUserId.value) return;
                const slim = messages.value.slice(-CHAT_HISTORY_MAX).map(m => ({
                    role: m.role,
                    text: m.text || '',
                    tools: m.tools || undefined,
                    evidenceOpen: false
                }));
                localStorage.setItem(key, JSON.stringify(slim));
                // 清掉旧版全局 key，防止串号
                localStorage.removeItem('gym_chat_history');
            } catch (e) { /* quota 满了就放弃，不影响主流程 */ }
        }

        function restoreChatHistory() {
            try {
                messages.value = [];
                if (!currentUserId.value) return;
                const key = chatHistoryKey(currentUserId.value);
                const raw = localStorage.getItem(key);
                if (!raw) return;
                const list = JSON.parse(raw);
                if (Array.isArray(list)) {
                    messages.value = list.slice(-CHAT_HISTORY_MAX);
                }
            } catch (e) {
                try { localStorage.removeItem(chatHistoryKey(currentUserId.value)); } catch (_) {}
            }
        }

        function clearChat() {
            if (streaming.value) return;
            messages.value = [];
            try { localStorage.removeItem(chatHistoryKey(currentUserId.value)); } catch (_) {}
            localStorage.removeItem('gym_chat_history');
            fetch(base + '/agent/memory', { method: 'DELETE', headers: authHeaders() }).catch(() => {});
            showToast('对话已清空', 'success');
        }

        // ---------- 对话 ----------
        function askPreset(q) {
            question.value = q;
            send();
        }

        /** P2-5：一键演示 */
        function runOneClickDemo() {
            question.value = '卧推涨没涨？下次举多少';
            send();
        }

        /** P1-1：Enter 发送，Shift+Enter 换行 */
        function onChatKeydown(e) {
            if (e.key !== 'Enter') return;
            if (e.shiftKey) return; // 换行
            e.preventDefault();
            send();
        }

        async function send() {
            const q = question.value.trim();
            if (!q || !loggedIn.value || streaming.value) return;

            messages.value.push({ role: 'me', text: q });
            messages.value.push({ role: 'agent', text: '', tools: [], evidenceOpen: false, agentName: '' });
            question.value = '';
            hasFirstChunk.value = false;
            streaming.value = true;
            typeQueue = Promise.resolve();
            scrollBottom();

            controller = new AbortController();
            try {
                // 主路径走同步 /agent/ask：DeepSeek+工具常要十几秒，SSE 等首包易被掐成「开小差」
                // 同步失败再试一次 SSE（带 status 心跳）；仍空才提示开小差
                mode.value = 'sync';
                await syncChat(q, controller.signal);
                await typeQueue;
                const agent = currentAgentMsg();
                if (agent && isPlaceholderAgentText(agent.text) && !controller.signal.aborted) {
                    agent.text = '';
                    mode.value = 'stream';
                    await streamChat(q, controller.signal);
                    await typeQueue;
                }
                const after = currentAgentMsg();
                if (after && isPlaceholderAgentText(after.text)) {
                    after.text = 'AI 教练暂时开小差了，请稍后再试';
                }
            } finally {
                streaming.value = false;
                controller = null;
                persistChatHistory();
                scrollBottom();
            }
        }

        function stop() {
            if (controller) controller.abort();
        }

        function isPlaceholderAgentText(t) {
            if (!t || !String(t).trim()) return true;
            const s = String(t).trim();
            return s === '正在思考...'
                || s.indexOf('正在查训练记录') >= 0
                || s === 'AI 教练暂时开小差了，请稍后再试';
        }

        /** 流式：手动解析 SSE 帧；识别 tool_call / tool_result（P0-1） */
        async function streamChat(q, signal) {
            const url = base + '/agent/chat?question=' + encodeURIComponent(q);
            let res;
            try {
                res = await fetch(url, {
                    headers: { 'Authorization': 'Bearer ' + token.value, 'Accept': 'text/event-stream' },
                    signal
                });
            } catch (e) {
                if (e.name !== 'AbortError') showToast('连接失败：' + e.message, 'error');
                return;
            }

            if (!await handleErrorResponse(res)) return;

            const reader = res.body.getReader();
            const decoder = new TextDecoder('utf-8');
            let buffer = '';
            let gotContent = false;
            try {
                while (true) {
                    const { done, value } = await reader.read();
                    if (done) break;
                    buffer += decoder.decode(value, { stream: true });
                    const frames = buffer.split('\n\n');
                    buffer = frames.pop();
                    for (const frame of frames) {
                        if (consumeSseFrame(frame)) gotContent = true;
                    }
                }
                if (buffer.trim()) {
                    if (consumeSseFrame(buffer)) gotContent = true;
                }
                if (!gotContent && !hasFirstChunk.value) {
                    const agent = currentAgentMsg();
                    const tip = 'AI 教练暂时开小差了，请稍后再试';
                    if (agent) agent.text = tip;
                    showToast(tip, 'error');
                }
            } catch (e) {
                const agent = currentAgentMsg();
                const tip = 'AI 教练暂时开小差了，请稍后再试';
                if (agent && !agent.text) agent.text = tip;
                if (e.name !== 'AbortError') showToast(tip, 'error');
            }
        }

        /** 解析一帧 SSE：支持 event:error / event:status（后端 AI 失败或查数中） */
        function consumeSseFrame(frame) {
            let eventName = 'message';
            let data = '';
            for (const line of frame.split('\n')) {
                if (line.startsWith('event:')) eventName = line.slice(6).trim();
                else if (line.startsWith('data:')) data += line.slice(5).trim();
            }
            if (!data) return false;
            if (eventName === 'error') {
                const agent = currentAgentMsg();
                if (agent) agent.text = data;
                showToast(data, 'error');
                hasFirstChunk.value = true;
                return true;
            }
            if (eventName === 'status') {
                // 心跳：占住连接，不把 JSON 当答案贴进气泡
                const agent = currentAgentMsg();
                if (agent && (!agent.text || agent.text === '正在思考...')) {
                    agent.text = '正在查训练记录…';
                }
                hasFirstChunk.value = true;
                return true;
            }
            handleSsePayload(data);
            return true;
        }

        /**
         * 解析单条 SSE data：若是工具事件 JSON 则走轨迹卡片，否则当普通文本追加。
         * 后端尚未推送时不影响现有纯文本流；推送后答辩可直接展示 Function Calling。
         */
        function handleSsePayload(raw) {
            if (!raw) return;
            try {
                const obj = JSON.parse(raw);
                if (obj && obj.type === 'tool_call') {
                    handleToolCall(obj);
                    return;
                }
                if (obj && obj.type === 'tool_result') {
                    handleToolResult(obj);
                    return;
                }
            } catch (e) { /* 普通文本 chunk，不是 JSON */ }
            appendAnswer(raw);
        }

        /** 工具中文名，方便答辩时一眼看懂调了啥 */
        const TOOL_LABELS = {
            getPersonalBest: '个人最好成绩',
            getStrengthTrend: '力量趋势',
            getMuscleVolume: '肌群容量分析',
            getRecentWorkouts: '近期训练记录',
            getBodyWeightTrend: '体重趋势',
            getTodayDiet: '当日饮食',
            getNutritionPlan: '营养计划'
        };

        function currentAgentMsg() {
            const last = messages.value[messages.value.length - 1];
            return (last && last.role === 'agent') ? last : null;
        }

        function handleToolCall(evt) {
            const msg = currentAgentMsg();
            if (!msg) return;
            if (!msg.tools) msg.tools = [];
            const exercise = (evt.args && (evt.args.exercise || evt.args.name)) || '';
            const label = TOOL_LABELS[evt.tool] || evt.tool || '未知工具';
            const callingLabel = exercise
                ? (exercise + (label.indexOf('最好') >= 0 ? '个人最好成绩' : label) + '...')
                : (label + '...');
            msg.tools.push({
                tool: evt.tool,
                args: evt.args || {},
                status: 'calling',
                callingLabel,
                doneLabel: '',
                summary: ''
            });
            scrollBottom();
        }

        function handleToolResult(evt) {
            const msg = currentAgentMsg();
            if (!msg || !msg.tools) return;
            // 优先匹配同名、仍在 calling 的条目
            let item = null;
            for (let i = msg.tools.length - 1; i >= 0; i--) {
                if (msg.tools[i].tool === evt.tool && msg.tools[i].status === 'calling') {
                    item = msg.tools[i];
                    break;
                }
            }
            if (!item) {
                item = { tool: evt.tool, args: {}, status: 'calling', callingLabel: '', doneLabel: '', summary: '' };
                msg.tools.push(item);
            }
            item.status = 'done';
            item.summary = evt.summary || '';
            item.doneLabel = evt.summary || ((TOOL_LABELS[evt.tool] || evt.tool) + ' 完成');
            scrollBottom();
        }

        function evidenceSummary(m) {
            if (!m.tools || !m.tools.length) return '—';
            const names = m.tools.map(t => TOOL_LABELS[t.tool] || t.tool);
            // 去重保序
            return [...new Set(names)].join('、');
        }

        function toggleEvidence(m) {
            m.evidenceOpen = !m.evidenceOpen;
        }

        /** 同步模式：失败时展示明确错误，绝不显示「（空回答）」 */
        async function syncChat(q, signal) {
            const agent = currentAgentMsg();
            if (agent) agent.text = '正在思考...';
            try {
                const res = await fetch(base + '/agent/ask', {
                    method: 'POST',
                    headers: authHeaders(),
                    body: JSON.stringify({ question: q }),
                    signal
                });
                const data = await res.json().catch(() => ({}));
                if (!res.ok || data.code !== 200) {
                    if (res.status === 401) {
                        markAuthExpired('登录过期，AI 回答没拿到——重新登录后可再问');
                        if (agent) agent.text = '登录过期了。点上方「重新登录」，对话还留着。';
                        return;
                    }
                    const tip = 'AI 教练暂时开小差了，请稍后再试';
                    if (agent) agent.text = tip;
                    showToast(tip, 'error');
                    return;
                }
                const answer = data.data && data.data.answer;
                if (!answer || !String(answer).trim()) {
                    // 绝不显示「（空回答）」
                    const tip = 'AI 教练暂时开小差了，请稍后再试';
                    if (agent) agent.text = tip;
                    showToast(tip, 'error');
                    return;
                }
                if (agent) {
                    agent.text = '';
                    if (data.data.agentName) agent.agentName = data.data.agentName;
                }
                appendAnswer(answer);
                await typeQueue;
            } catch (e) {
                if (agent) agent.text = 'AI 教练暂时开小差了，请稍后再试';
                if (e.name !== 'AbortError') showToast('AI 教练暂时开小差了，请稍后再试', 'error');
            }
        }

        /**
         * P2-4：打字机效果。短 chunk / 超长 chunk 直接落盘，避免卡顿。
         */
        function appendAnswer(chunk) {
            if (!chunk) return;
            if (!hasFirstChunk.value) hasFirstChunk.value = true;

            if (chunk.length <= 5 || chunk.length > 200) {
                appendAnswerImmediate(chunk);
                return;
            }
            typeQueue = typeQueue.then(async () => {
                for (let i = 0; i < chunk.length; i += 2) {
                    appendAnswerImmediate(chunk.slice(i, i + 2));
                    await sleep(10);
                }
            });
        }

        function appendAnswerImmediate(chunk) {
            const last = messages.value[messages.value.length - 1];
            if (last && last.role === 'agent') {
                last.text += chunk;
                scrollBottom();
            }
        }

        function sleep(ms) {
            return new Promise(r => setTimeout(r, ms));
        }

        function pushSystem(text) {
            messages.value.push({ role: 'system', text });
            scrollBottom();
        }

        /** 统一处理非 2xx；状态码只打 console，界面说白话（P1-6） */
        async function handleErrorResponse(res) {
            if (res.ok) return true;
            let msg = '请再试一次';
            try {
                const data = await res.json();
                msg = data.message || msg;
            } catch (e) { /* 非 JSON */ }
            console.warn('HTTP', res.status, msg);
            if (res.status === 401) {
                markAuthExpired();
            } else if (res.status === 403) {
                showToast('你没有权限访问这个功能', 'error');
            } else {
                showToast('网络不稳定，请再试一次', 'error');
            }
            return false;
        }

        // ---------- 演示数据 + P0-2 图表 ----------
        async function loadHighlights() {
            busy.value = true;
            try {
                const res = await fetch(base + '/demo/highlights', { headers: authHeaders() });
                if (!await handleErrorResponse(res)) return;
                const data = await res.json();
                highlights.value = data.data || [];
                renderDemoCharts();
                showToast('已加载演示结论', 'success');
            } catch (e) {
                showToast('读取失败：' + e.message, 'error');
            } finally {
                busy.value = false;
            }
        }

        async function regenerate() {
            busy.value = true;
            demoResult.value = '正在生成 26 周训练日志，请稍候…';
            try {
                const res = await fetch(base + '/demo/regenerate', {
                    method: 'POST', headers: authHeaders()
                });
                if (!await handleErrorResponse(res)) { demoResult.value = ''; return; }
                const data = await res.json();
                demoResult.value = '已生成：' + data.data.sessionCount + ' 次训练 / '
                    + data.data.setCount + ' 组 / 体重记录 ' + data.data.bodyRecordCount + ' 条';
                renderDemoCharts();
                showToast('示例训练数据已生成', 'success');
            } catch (e) {
                demoResult.value = '生成失败：' + e.message;
                showToast('生成失败：' + e.message, 'error');
            } finally {
                busy.value = false;
            }
        }

        async function clearDemo() {
            if (!await askConfirm('确定清空示例训练数据？', '清空示例')) return;
            busy.value = true;
            try {
                const res = await fetch(base + '/demo/clear', { method: 'POST', headers: authHeaders() });
                if (!await handleErrorResponse(res)) return;
                demoResult.value = '已清空示例数据';
                showToast('示例数据已清空', 'success');
                lastSession.exercises = [];
                today.sets = [];
                today.sessionId = null;
            } catch (e) {
                demoResult.value = '清空失败：' + e.message;
                showToast('清空失败：' + e.message, 'error');
            } finally {
                busy.value = false;
            }
        }

        /**
         * 按 DemoDataPlanner 剧本在前端推算 26 周曲线。
         * 后端暂无图表接口，口径与演示种子一致，答辩时能对上「卧推平台期」叙事。
         */
        function buildDemoChartSeries() {
            const WEEKS = 26;
            // 0 LINEAR, 1 SLOWED, 2 PLATEAU, 3 DELOAD
            const phases = new Array(WEEKS);
            for (let i = 0; i < 8; i++) phases[i] = 0;
            phases[8] = 3;
            for (let i = 9; i < 15; i++) phases[i] = 1;
            phases[15] = 3;
            for (let i = 16; i < 26; i++) phases[i] = 2;

            const gains = {
                bench: [2.5, 1.25, 0, 0],
                squat: [5.0, 2.5, 1.25, 0],
                deadlift: [5.0, 2.5, 1.25, 0]
            };
            let bench = 60, squat = 80, deadlift = 100;
            const weeks = [], b = [], s = [], d = [], vol = [];
            // 一周四练正式组合计约 39；减载减半；第 12 周（index 11）出差整周缺练
            const BASE_SETS = 39;
            const TRAVEL_WEEK = 11;

            for (let w = 0; w < WEEKS; w++) {
                const ph = phases[w];
                if (ph !== 3) {
                    bench += gains.bench[ph];
                    squat += gains.squat[ph];
                    deadlift += gains.deadlift[ph];
                }
                weeks.push('W' + (w + 1));
                b.push(+bench.toFixed(1));
                s.push(+squat.toFixed(1));
                d.push(+deadlift.toFixed(1));
                if (w === TRAVEL_WEEK) vol.push(0);
                else if (ph === 3) vol.push(Math.round(BASE_SETS / 2));
                else vol.push(BASE_SETS);
            }
            return { weeks, bench: b, squat: s, deadlift: d, volume: vol };
        }

        /** 无真实记录时画前端推算的样板图（挂在示例 DOM，避免冒充用户数据） */
        function renderDemoCharts() {
            if (typeof echarts === 'undefined') return;
            const data = buildDemoChartSeries();
            nextTick(() => {
                if (chartDemoBig3.value) {
                    if (!chartDemoBig3Inst) chartDemoBig3Inst = echarts.init(chartDemoBig3.value);
                    chartDemoBig3Inst.setOption({
                        backgroundColor: 'transparent',
                        color: ['#d6ff00', '#3dff9a', '#ff4f1a'],
                        textStyle: { color: '#8d97a8' },
                        tooltip: { trigger: 'axis' },
                        legend: { data: ['卧推', '深蹲', '硬拉'], textStyle: { color: '#8d97a8' } },
                        grid: { left: 48, right: 20, top: 40, bottom: 32 },
                        xAxis: { type: 'category', data: data.weeks, axisLine: { lineStyle: { color: '#2a3340' } } },
                        yAxis: { type: 'value', name: 'kg', splitLine: { lineStyle: { color: '#232a35' } } },
                        series: [
                            { name: '卧推', type: 'line', smooth: true, data: data.bench },
                            { name: '深蹲', type: 'line', smooth: true, data: data.squat },
                            { name: '硬拉', type: 'line', smooth: true, data: data.deadlift }
                        ]
                    }, true);
                }
                if (chartDemoVol.value) {
                    if (!chartDemoVolInst) chartDemoVolInst = echarts.init(chartDemoVol.value);
                    chartDemoVolInst.setOption({
                        backgroundColor: 'transparent',
                        color: ['#ff7a45'],
                        textStyle: { color: '#8d97a8' },
                        tooltip: { trigger: 'axis' },
                        grid: { left: 48, right: 20, top: 30, bottom: 32 },
                        xAxis: { type: 'category', data: data.weeks, axisLine: { lineStyle: { color: '#2a3340' } } },
                        yAxis: { type: 'value', name: '组数', splitLine: { lineStyle: { color: '#232a35' } } },
                        series: [{ name: '每周组数', type: 'bar', data: data.volume, itemStyle: { borderRadius: [6, 6, 0, 0] } }]
                    }, true);
                }
            });
        }

        // ---------- 自检 ----------
        async function loadToolCount() {
            try {
                const res = await fetch(base + '/agent/tools/count', { headers: authHeaders() });
                if (!res.ok) return;
                const data = await res.json();
                toolCount.value = data.data.count;
            } catch (e) { /* 自检失败不打扰 */ }
        }

        /**
         * 系统自检：AI 灯必须真发一句话，不能只测 tools/count 通不通。
         * 否则会出现「绿灯但对话空回答」的谎言。
         */
        async function runHealthCheck() {
            health.checking = true;
            try {
                const res = await fetch(base + '/agent/tools/count', { headers: authHeaders() });
                if (res.ok) {
                    const data = await res.json();
                    const cnt = (data.data && data.data.count) || 0;
                    health.tools = cnt > 0;
                    health.toolCount = cnt;
                    toolCount.value = cnt;
                    health.redis = true;
                } else {
                    health.tools = false;
                    health.redis = res.status !== 0;
                }
            } catch (e) {
                health.tools = false;
                health.redis = false;
            }

            try {
                const res = await fetch(base + '/api/stats/overview', { headers: authHeaders() });
                health.mysql = res.ok;
            } catch (e) {
                health.mysql = false;
            }

            // 真调一轮模型：问一句极短的话，有非空 answer 才算绿灯
            try {
                const res = await fetch(base + '/agent/ask', {
                    method: 'POST', headers: authHeaders(),
                    body: JSON.stringify({ question: '用一句话介绍你自己' }),
                    signal: AbortSignal.timeout ? AbortSignal.timeout(45000) : undefined
                });
                const data = await res.json().catch(() => ({}));
                const answer = data.data && data.data.answer;
                health.ai = res.ok && data.code === 200 && !!(answer && String(answer).trim());
            } catch (e) {
                health.ai = false;
            }

            health.checking = false;
            showToast(health.ai ? '自检完成：AI 正常' : '自检完成：AI 异常', health.ai ? 'success' : 'error');
        }

        // ---------- 场馆管理 ----------
        async function loadGym() {
            if (!loggedIn.value) return;
            gymBusy.value = true;
            try {
                const [m, c, co, e, b, cd, cr, s] = await Promise.all([
                    fetch(base + '/api/members', { headers: authHeaders() }).then(r => r.json()),
                    fetch(base + '/api/coaches', { headers: authHeaders() }).then(r => r.json()),
                    fetch(base + '/api/courses', { headers: authHeaders() }).then(r => r.json()),
                    fetch(base + '/api/equipment', { headers: authHeaders() }).then(r => r.json()),
                    fetch(base + '/api/bookings', { headers: authHeaders() }).then(r => r.json()),
                    fetch(base + '/api/cards', { headers: authHeaders() }).then(r => r.json()),
                    fetch(base + '/api/consume/records', { headers: authHeaders() }).then(r => r.json()),
                    fetch(base + '/api/stats/overview', { headers: authHeaders() }).then(r => r.json())
                ]);
                members.value = m.data || [];
                coaches.value = c.data || [];
                courses.value = co.data || [];
                equipment.value = e.data || [];
                bookings.value = b.data || [];
                cards.value = cd.data || [];
                consumeRecords.value = cr.data || [];
                stats.value = s.data || null;
                gymLoaded.value = true;
            } catch (e) {
                gymMsg.value = '加载失败：' + e.message;
                showToast('场馆数据加载失败：' + e.message, 'error');
            } finally {
                gymBusy.value = false;
            }
        }

        async function checkInDemo() {
            gymBusy.value = true;
            gymMsg.value = '';
            try {
                const res = await fetch(base + '/api/consume/check-in?memberId=1', { method: 'POST', headers: authHeaders() });
                const data = await res.json();
                if (data.code === 200) {
                    // P0-5：不写接口路径，给用户看白话
                    gymMsg.value = '签到成功，已扣 1 次课时';
                    showToast('签到成功，已扣 1 次课时', 'success');
                    await loadGym();
                } else {
                    gymMsg.value = '签到失败：' + (data.message || '');
                    showToast('签到失败：' + (data.message || ''), 'error');
                }
            } catch (e) {
                gymMsg.value = '签到失败：' + e.message;
                showToast('签到失败：' + e.message, 'error');
            } finally {
                gymBusy.value = false;
            }
        }

        async function bookDemo() {
            gymBusy.value = true;
            gymMsg.value = '';
            try {
                const list = await fetch(base + '/api/bookings?memberId=1', { headers: authHeaders() }).then(r => r.json());
                const existing = (list.data || []).find(b => b.courseId === 1 && b.status === 1);
                if (existing) {
                    await fetch(base + '/api/bookings/' + existing.id + '/cancel', { method: 'POST', headers: authHeaders() }).then(r => r.json());
                }
                const res = await fetch(base + '/api/bookings/book?memberId=1&courseId=1', { method: 'POST', headers: authHeaders() });
                const data = await res.json();
                if (data.code === 200) {
                    gymMsg.value = '预约成功，课程「已约」名额 +1';
                    showToast('预约成功', 'success');
                    await loadGym();
                } else {
                    gymMsg.value = '预约失败：' + (data.message || '');
                    showToast('预约失败：' + (data.message || ''), 'error');
                }
            } catch (e) {
                gymMsg.value = '预约失败：' + e.message;
                showToast('预约失败：' + e.message, 'error');
            } finally {
                gymBusy.value = false;
            }
        }

        function goalText(g) { return ({ 1: '增肌', 2: '减脂', 3: '力量', 4: '塑形', 5: '健康' })[g] || '—'; }
        function statusText(s) { return ({ 1: '正常', 2: '挂失', 3: '冻结', 4: '注销' })[s] || '—'; }
        function courseStatusText(s) { return ({ 1: '可预约', 2: '已满', 3: '已结束', 4: '已取消' })[s] || '—'; }
        function equipStatusText(s) { return ({ 1: '正常', 2: '报修', 3: '停用' })[s] || '—'; }
        function bookingStatusText(s) { return ({ 1: '已预约', 2: '已完成', 3: '已取消', 4: '超时' })[s] || '—'; }
        function genderText(g) { return ({ 0: '未知', 1: '男', 2: '女' })[g] || '—'; }
        function levelText(l) { return ({ 1: '初级', 2: '中级', 3: '高级' })[l] || '—'; }
        function cardTypeText(t) { return ({ 1: '次卡', 2: '月卡', 3: '年卡', 4: '私教课包' })[t] || '—'; }
        function cardStatusText(s) { return ({ 1: '有效', 2: '已用完', 3: '已过期', 4: '已停用' })[s] || '—'; }
        function categoryText(c) { return ({ 1: '有氧', 2: '力量', 3: '自由重量', 4: '功能' })[c] || '—'; }
        function consumeTypeText(t) { return ({ 1: '课程消课', 2: '入场消次' })[t] || '—'; }

        function toggleMoreMenu(key) {
            moreMenu.value = moreMenu.value === key ? null : key;
        }

        // ---------- 通用 CRUD ----------
        const crudConfig = {
            member: { api: '/api/members', title: '会员', fields: [
                { k: 'name', label: '姓名', type: 'text', required: true },
                { k: 'phone', label: '电话', type: 'text' },
                { k: 'gender', label: '性别', type: 'select', options: [[0,'未知'],[1,'男'],[2,'女']] },
                { k: 'goal', label: '目标', type: 'select', options: [[1,'增肌'],[2,'减脂'],[3,'力量'],[4,'塑形'],[5,'健康']] },
                { k: 'trainLevel', label: '训练水平', type: 'select', options: [[1,'新手'],[2,'中级'],[3,'高级']] },
                { k: 'birthday', label: '生日', type: 'date' },
                { k: 'joinDate', label: '入会日期', type: 'date' },
                { k: 'heightCm', label: '身高(cm)', type: 'number' },
                { k: 'weightKg', label: '体重(kg)', type: 'number' },
                { k: 'status', label: '状态', type: 'select', options: [[1,'正常'],[2,'挂失'],[3,'冻结'],[4,'注销']] }
            ]},
            coach: { api: '/api/coaches', title: '教练', fields: [
                { k: 'name', label: '姓名', type: 'text', required: true },
                { k: 'phone', label: '电话', type: 'text' },
                { k: 'level', label: '级别', type: 'select', options: [[1,'初级'],[2,'中级'],[3,'高级']] },
                { k: 'specialty', label: '专长', type: 'text' },
                { k: 'intro', label: '简介', type: 'text' }
            ]},
            course: { api: '/api/courses', title: '课程', fields: [
                { k: 'name', label: '名称', type: 'text', required: true },
                { k: 'coachId', label: '教练ID', type: 'number' },
                { k: 'capacity', label: '容量', type: 'number' },
                { k: 'bookedCnt', label: '已约', type: 'number' },
                { k: 'courseTime', label: '开课时间', type: 'datetime' },
                { k: 'durationMin', label: '时长(分)', type: 'number' },
                { k: 'room', label: '教室', type: 'text' },
                { k: 'status', label: '状态', type: 'select', options: [[1,'可预约'],[2,'已满'],[3,'已结束'],[4,'已取消']] }
            ]},
            card: { api: '/api/cards', createUrl: '/api/cards/open', title: '卡项', fields: [
                { k: 'memberId', label: '会员ID', type: 'number', required: true },
                { k: 'cardName', label: '卡名称', type: 'text', required: true },
                { k: 'cardType', label: '类型', type: 'select', options: [[1,'次卡'],[2,'月卡'],[3,'年卡'],[4,'私教课包']] },
                { k: 'totalCount', label: '总次数', type: 'number' },
                { k: 'remainCount', label: '剩余次数', type: 'number' },
                { k: 'startDate', label: '起始日', type: 'date' },
                { k: 'endDate', label: '到期日', type: 'date' },
                { k: 'price', label: '价格', type: 'number' },
                { k: 'status', label: '状态', type: 'select', options: [[1,'有效'],[2,'已用完'],[3,'已过期'],[4,'已停用']] }
            ]},
            booking: { api: '/api/bookings', createUrl: '/api/bookings/book', noEdit: true, title: '预约', fields: [
                { k: 'memberId', label: '会员ID', type: 'number', required: true },
                { k: 'courseId', label: '课程ID', type: 'number', required: true }
            ]},
            equipment: { api: '/api/equipment', title: '器材', fields: [
                { k: 'name', label: '名称', type: 'text', required: true },
                { k: 'category', label: '类别', type: 'select', options: [[1,'有氧'],[2,'力量'],[3,'自由重量'],[4,'功能']] },
                { k: 'location', label: '位置', type: 'text' },
                { k: 'status', label: '状态', type: 'select', options: [[1,'正常'],[2,'报修'],[3,'停用']] },
                { k: 'buyDate', label: '购买日', type: 'date' },
                { k: 'lastMaint', label: '维护日', type: 'date' },
                { k: 'note', label: '备注', type: 'text' }
            ]}
        };

        const currentFields = computed(() => (crudConfig[formEntity.value] && crudConfig[formEntity.value].fields) || []);
        const formTitle = computed(() => (formId.value ? '编辑' : '新增') + (crudConfig[formEntity.value] ? crudConfig[formEntity.value].title : ''));

        function openForm(entity, rec) {
            formEntity.value = entity;
            formId.value = rec ? rec.id : null;
            const cfg = crudConfig[entity];
            const model = {};
            cfg.fields.forEach(f => { model[f.k] = ''; });
            if (rec) {
                Object.assign(model, JSON.parse(JSON.stringify(rec)));
                cfg.fields.forEach(f => { if (model[f.k] === null) model[f.k] = ''; });
            }
            Object.keys(formModel).forEach(k => delete formModel[k]);
            Object.assign(formModel, model);
            Object.keys(formErrors).forEach(k => delete formErrors[k]);
            form.open = true;
            moreMenu.value = null;
        }

        function closeForm() { form.open = false; formId.value = null; }

        async function saveCrud() {
            const entity = formEntity.value;
            const cfg = crudConfig[entity];
            Object.keys(formErrors).forEach(k => delete formErrors[k]);
            let hasErr = false;
            for (const f of cfg.fields) {
                if (f.required && !formModel[f.k] && formModel[f.k] !== 0) {
                    formErrors[f.k] = '请填写' + f.label;
                    hasErr = true;
                }
            }
            if (hasErr) return;
            gymBusy.value = true;
            try {
                const body = JSON.parse(JSON.stringify(formModel));
                delete body.id; delete body.deleted; delete body.version;
                let url, method;
                if (formId.value) { url = cfg.api + '/' + formId.value; method = 'PUT'; }
                else { url = cfg.createUrl || cfg.api; method = 'POST'; }
                const res = await fetch(base + url, { method, headers: authHeaders(), body: JSON.stringify(body) }).then(r => r.json());
                if (res.code === 200) {
                    closeForm();
                    await loadGym();
                    gymMsg.value = (formId.value ? '修改' : '新增') + '成功';
                    showToast((formId.value ? '修改' : '新增') + '成功', 'success');
                } else {
                    gymMsg.value = '保存失败：' + (res.message || '');
                    showToast('保存失败：' + (res.message || '请再试一次'), 'error');
                }
            } catch (e) {
                console.warn('saveCrud', e);
                gymMsg.value = '保存失败';
                showToast('网络不稳定，请再试一次', 'error');
            } finally { gymBusy.value = false; }
        }

        async function deleteCrud(entity, id) {
            if (!await askConfirm('确定删除该记录？', '删除')) return;
            const cfg = crudConfig[entity];
            const res = await fetch(base + cfg.api + '/' + id, { method: 'DELETE', headers: authHeaders() }).then(r => r.json());
            if (res.code === 200) {
                await loadGym();
                gymMsg.value = '删除成功';
                showToast('删除成功', 'success');
            } else {
                gymMsg.value = '删除失败：' + (res.message || '');
                showToast('删除失败：' + (res.message || ''), 'error');
            }
        }

        async function domainAction(entity, id, label, url) {
            if (!await askConfirm(label + '？', '确认操作')) return;
            const res = await fetch(base + url, { method: 'POST', headers: authHeaders() }).then(r => r.json());
            if (res.code === 200) {
                await loadGym();
                gymMsg.value = label + '成功';
                showToast(label + '成功', 'success');
            } else {
                gymMsg.value = label + '失败：' + (res.message || '');
                showToast(label + '失败：' + (res.message || ''), 'error');
            }
        }

        async function cardRenew(id) {
            // 输入弹窗已确认意图，不再二次 confirm
            const n = await askPrompt('要追加多少次？', '5', '追加次数', '续费');
            if (n === null) return;
            const res = await fetch(base + '/api/cards/' + id + '/renew?addCount=' + encodeURIComponent(n), {
                method: 'POST', headers: authHeaders()
            }).then(r => r.json());
            if (res.code === 200) {
                await loadGym();
                showToast('续费成功', 'success');
            } else {
                showToast('续费失败：' + (res.message || ''), 'error');
            }
        }

        async function cardExtend(id) {
            const d = await askPrompt('要顺延多少天？', '30', '顺延天数', '延期');
            if (d === null) return;
            const res = await fetch(base + '/api/cards/' + id + '/extend?days=' + encodeURIComponent(d), {
                method: 'POST', headers: authHeaders()
            }).then(r => r.json());
            if (res.code === 200) {
                await loadGym();
                showToast('延期成功', 'success');
            } else {
                showToast('延期失败：' + (res.message || ''), 'error');
            }
        }

        // ---------- 工具 ----------
        function scrollBottom() {
            nextTick(() => {
                if (chatWindow.value) chatWindow.value.scrollTop = chatWindow.value.scrollHeight;
            });
        }

        function renderText(text) {
            if (!text) return '';
            const escaped = text
                .replace(/&/g, '&amp;')
                .replace(/</g, '&lt;')
                .replace(/>/g, '&gt;');
            return escaped.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>');
        }

        onMounted(() => {
            restoreChatHistory();
            if (loggedIn.value) {
                loadToolCount();
                loadTrainPage();
                flushOfflineQueue();
                loadHabit();
            } else {
                scrubLoginForm();
                [50, 200, 500, 1000, 2000].forEach(ms => setTimeout(purgeAutofillDemo, ms));
            }
            document.addEventListener('click', () => { moreMenu.value = null; });
            // 切回前台时若动作库空着，自动再拉——别逼用户狂点重试
            document.addEventListener('visibilitychange', () => {
                if (document.visibilityState !== 'visible' || !loggedIn.value) return;
                flushOfflineQueue();
                if (tab.value === 'train' && !exerciseList.value.length && !exercisesLoading.value) {
                    loadExercises();
                }
            });
            window.addEventListener('online', () => flushOfflineQueue());
            window.addEventListener('resize', () => {
                if (chart1Inst) chart1Inst.resize();
                if (chart2Inst) chart2Inst.resize();
                if (chart3Inst) chart3Inst.resize();
                if (chartDemoBig3Inst) chartDemoBig3Inst.resize();
                if (chartDemoVolInst) chartDemoVolInst.resize();
            });
            const splash = document.getElementById('boot-splash');
            if (splash) {
                splash.classList.add('hide');
                setTimeout(() => { if (splash.parentNode) splash.remove(); }, 200);
            }
        });

        watch(() => messages.value.length, scrollBottom);
        watch(messages, persistChatHistory, { deep: true });
        watch(() => tab.value, (t) => {
            // 离开打卡页就收起记组垫板——AI 页底下还挂着动作要点太分心
            if (t !== 'train' && pad.open) {
                closePad();
            }
            // 非管理员误进后台：立刻踢回打卡
            if (t === 'gym' && !isAdmin.value) {
                tab.value = 'train';
                return;
            }
            if (t === 'gym' && isAdmin.value) {
                if (!gymLoaded.value) loadGym();
                return;
            }
            if (t === 'me') loadMyPage();
            if (t === 'train') loadTrainPage();
        });
        watch(meSub, (s) => {
            if (tab.value !== 'me') return;
            if (s === 'mine') { loadMyCharts(); loadHabit(); }
            if (s === 'venue') loadVenue();
            if (s === 'plan') loadPortalExtras();
            if (s === 'history') loadHistory();
        });

        return {
            base, token, loggedIn, authExpired, role, isAdmin, roleText, memberHello, displayName, tab, meSub, hasRealTrainingData, mode,
            demoStoryLock, restPrefSec, restCustomSec, startCustomRest,
            chartLoadError, muscleVolume, volumeGapLine, askVolumeGap, plateauAlerts, askPlateau,
            plateauNextPlan, applyPlateauPlan, nextSessionTip, seedDemoStory, seedBusy,
            weekPlan, reminders, intensityHint, weekdayLabel, switchMeSub,
            habitStats, weeklyReviewText, reviewBusy, runWeeklyReview, askAboutReview,
            historyList, historyBusy, historyOpenId, historyDetail, loadHistory, toggleHistory,
            loadHistoryDetail, reuseHistorySession, deleteHistorySet, deleteTodaySet,
            planSaving, planDirty, markPlanDirty, addPlanExercise, removePlanExercise, toggleRestDay,
            saveWeekPlanNow, regenerateWeekPlan, applySuggestWeight, askWhySuggest, offlineQueue,
            venueCourses, venueBookings, venueCards, venueConsume, venueBusy, venueMsg,
            loadVenue, bookCourse, cancelBooking, memberCheckIn,
            dietHistory, deleteDietItem, dietGoalLine, weightGapLine, dietFavs, saveDietAsFav,
            dietFoodQ, dietCat, dietCats, dietSearchOpen, filteredFoods, dietQuickFoods,
            pickFoodAndLog, normalizeFood, copyYesterdayDiet, dietCalPct, dietProPct,
            knowledgeQ, knowledgeA, knowledgeBusy, askKnowledge,
            planDone, togglePlanDone, applyTodayPlan, visibleReminders, dismissReminder,
            selfCheck, runSelfCheck,
            bodyWeightSeries, dietDay, dietForm, dietBusy, dietPresets, bodySnap, bodyForm, bodyBusy,
            profile, profileForm, profileBusy, loadProfile, saveProfile,
            nutriBusy, nutriSuggest, suggestNutrition,
            accountInfo, accountBusy, bindForm, pwdForm, bindPhone, changePassword, loadAccountInfo,
            onboard, openOnboard, skipOnboard, finishOnboard,
            loadDietAndBody, applyDietPreset, saveDietQuick, saveBodyQuick, startRestTimer,
            question, messages, streaming, hasFirstChunk,
            busy, loggingIn, toolCount, highlights, demoResult, chatWindow, chart1El, chart2El, chart3El,
            chartDemoBig3, chartDemoVol, chartNotes, chartNotesDisplay, chartSparse, chartSparseTip,
            authMode, loginWay, authFormKey, loginForm, loginFieldErrors, padFieldErrors, formErrors, padSaving, dialog,
            scrubLoginForm, switchAuthMode, fillDemoAccount, onLoginUsernameInput, onLoginPasswordInput,
            isPhoneAccount, sendSmsCode, submitAuth, loginBySms, resetPassword,
            authPageTitle, authSubmitLabel, smsBtnText, smsCountdown, smsSending,
            confirmRelogin, volumeBarPct, volumeVerdictClass, volumeVerdictText,
            todayHeroLine, todaySummaryLine,
            presets, canSend, loginError, toasts, health, moreMenu,
            login, register, logout, send, stop, askPreset, clearChat, runOneClickDemo, onChatKeydown,
            showToast, healthClass, runHealthCheck, toggleEvidence, evidenceSummary, toggleMoreMenu,
            resolveDialog,
            loadHighlights, regenerate, clearDemo, loadToolCount, renderText, loadMyPage, loadMyCharts,
            gymLoaded, gymBusy, gymMsg, members, coaches, courses, equipment, bookings, stats,
            gymTab, cards, consumeRecords, form, formEntity, formModel, currentFields, formTitle,
            loadGym, checkInDemo, bookDemo,
            openForm, closeForm, saveCrud, deleteCrud, domainAction, cardRenew, cardExtend,
            goalText, statusText, courseStatusText, equipStatusText, bookingStatusText,
            genderText, levelText, cardTypeText, cardStatusText, categoryText, consumeTypeText,
            trainBusy, lastSession, today, exerciseList, recentExercises, exercisesLoading, exTag, exKeyword, pad, rest, injuryWarn, devMsg,
            customEx, customExBusy, openCustomExercise, saveCustomExercise,
            exerciseListExpanded, exercisePreviewLimit, visibleExercises, quickExercises, shortExName,
            todayListOpen, expandedSetId, moreToolsOpen,
            loadTrainPage, loadExercises, loadRecentExercises, setExTag, againSet, pickExercise, closePad, toggleSetDetail,
            applyLastWeight, setPadRpe, toggleWarmup, formatRest, stopRestTimer, bumpRest,
            startCheckIn, goTrain, goMe, goForum, goGym, onPadChange, bump, nextSet, confirmPad,
            reportDeviation, triggerRemind,
            userId, forumView, forumSort, forumCategory, forumPosts, forumDetail, forumBusy,
            forumCommentText, forumReplyTo, forumForm, adminForumPosts, adminForumBusy, adminForumStatus,
            forumCatText, switchForumView, backForumList, loadForumList, openForumPost,
            submitForumPost, submitForumComment, toggleForumLike, toggleForumFav, deleteForumPost,
            loadAdminForum, auditForumPost, adminDeleteForum
        };
    }
}).mount('#app');
} catch (bootErr) {
    // setup 抛错时 Vue 不会替换模板，用户会看到满屏 {{ }} —— 给一个可操作的兜底
    console.error('Iron Log boot failed', bootErr);
    try {
        ['gym_plan_done', 'gym_remind_dismiss', 'gym_diet_favs', 'gym_chat_history'].forEach(function (k) {
            try { localStorage.removeItem(k); } catch (_) {}
        });
        Object.keys(localStorage).forEach(function (k) {
            if (k.indexOf('gym_chat_history') === 0) {
                try { localStorage.removeItem(k); } catch (_) {}
            }
        });
    } catch (_) {}
    var appEl = document.getElementById('app');
    if (appEl) {
        appEl.innerHTML = '<div style="padding:24px;color:#e8eaed;font-family:sans-serif;line-height:1.6">'
            + '<h2 style="margin:0 0 12px">页面启动失败</h2>'
            + '<p style="opacity:.85">多半是浏览器缓存了坏数据。点下面按钮清掉本地缓存后刷新。</p>'
            + '<button type="button" style="margin-top:12px;padding:10px 16px;border:0;border-radius:8px;'
            + 'background:#5eead4;color:#042f2e;font-weight:700" onclick="location.reload()">刷新重试</button>'
            + '<pre style="margin-top:16px;opacity:.55;white-space:pre-wrap;font-size:12px"></pre></div>';
        var pre = appEl.querySelector('pre');
        if (pre) pre.textContent = String(bootErr && (bootErr.stack || bootErr.message || bootErr));
    }
}
