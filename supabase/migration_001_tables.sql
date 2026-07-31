-- AI-Live-Overflow: Supabase 数据库初始化
-- 桌宠 ↔ AI 双向通信的核心表结构

-- 1. 手势日志：每次戳桌宠都会上报
create table if not exists gesture_log (
    id bigserial primary key,
    gesture_type text not null,  -- tap, double_tap, long_press, fling
    x integer,
    y integer,
    created_at timestamptz default now()
);

-- 2. 前台App检测：记录用户打开了什么App
create table if not exists app_usage (
    id bigserial primary key,
    package_name text not null,     -- 如 com.ss.android.ugc.aweme (抖音)
    app_name text,                  -- 人类可读名称
    started_at timestamptz default now()
);

-- 3. 桌宠状态：AI可以主动写入，桌宠实时读取
-- state_key 如: mood, speech_bubble, expression, heat, accessory
create table if not exists pet_state (
    id bigserial primary key,
    state_key text not null,
    state_value text,
    updated_at timestamptz default now()
);

-- 4. AI消息队列：AI发消息给桌宠（气泡、动作指令等）
create table if not exists ai_messages (
    id bigserial primary key,
    message_type text not null,     -- bubble, expression, action, notification
    content text,                   -- 气泡文字、表情名、动作名
    duration_seconds integer default 5,
    delivered boolean default false,
    created_at timestamptz default now()
);

-- 5. 截图检测日志
create table if not exists screenshot_log (
    id bigserial primary key,
    file_name text,
    detected_at timestamptz default now()
);

-- ========== 索引 ==========
create index if not exists idx_gesture_log_created_at on gesture_log(created_at desc);
create index if not exists idx_ai_messages_delivered on ai_messages(delivered, created_at desc);
create index if not exists idx_pet_state_key on pet_state(state_key);

-- ========== RLS (Row Level Security) ==========
-- 个人项目用 anon key + RLS 足够了
alter table gesture_log enable row level security;
alter table app_usage enable row level security;
alter table pet_state enable row level security;
alter table ai_messages enable row level security;
alter table screenshot_log enable row level security;

-- anon key 可以读写所有表（个人项目足够安全）
create policy "anon_all_gesture_log" on gesture_log for all using (true) with check (true);
create policy "anon_all_app_usage" on app_usage for all using (true) with check (true);
create policy "anon_all_pet_state" on pet_state for all using (true) with check (true);
create policy "anon_all_ai_messages" on ai_messages for all using (true) with check (true);
create policy "anon_all_screenshot_log" on screenshot_log for all using (true) with check (true);

-- ========== Realtime ==========
-- 开启 Realtime 支持，桌宠可以订阅变化
alter publication supabase_realtime add table pet_state;
alter publication supabase_realtime add table ai_messages;
