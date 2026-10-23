CREATE TABLE words (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                word TEXT NOT NULL UNIQUE,
                phonetic TEXT NOT NULL,
                meaning TEXT NOT NULL,
                context_meaning TEXT NOT NULL,
                example TEXT NOT NULL,
                state TEXT NOT NULL DEFAULT 'NEW',
                weak INTEGER NOT NULL DEFAULT 0,
                seen_count INTEGER NOT NULL DEFAULT 0,
                correct_count INTEGER NOT NULL DEFAULT 0,
                next_review_at INTEGER NOT NULL DEFAULT 0
            );
CREATE TABLE study_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                event_type TEXT NOT NULL,
                correct INTEGER NOT NULL,
                created_at INTEGER NOT NULL
            );
CREATE TABLE ai_cache (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                content TEXT NOT NULL,
                model TEXT NOT NULL,
                created_at INTEGER NOT NULL
            );
CREATE TABLE ai_usage (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                model TEXT NOT NULL,
                prompt_tokens INTEGER NOT NULL,
                completion_tokens INTEGER NOT NULL,
                estimated_cost REAL NOT NULL,
                created_at INTEGER NOT NULL
            );
ALTER TABLE words ADD COLUMN part_of_speech TEXT NOT NULL DEFAULT '';
ALTER TABLE words ADD COLUMN importance INTEGER NOT NULL DEFAULT 2;
ALTER TABLE words ADD COLUMN familiarity INTEGER NOT NULL DEFAULT 0;
ALTER TABLE words ADD COLUMN mistake_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE words ADD COLUMN review_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE words ADD COLUMN last_review_at INTEGER NOT NULL DEFAULT 0;
ALTER TABLE words ADD COLUMN created_at INTEGER NOT NULL DEFAULT 0;
ALTER TABLE words ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0;
ALTER TABLE words ADD COLUMN streak INTEGER NOT NULL DEFAULT 0;
ALTER TABLE words ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0;
ALTER TABLE words ADD COLUMN example_translation TEXT NOT NULL DEFAULT '';
ALTER TABLE study_events ADD COLUMN word_id INTEGER;
CREATE TABLE daily_plan (day TEXT NOT NULL, word_id INTEGER NOT NULL, kind TEXT NOT NULL, PRIMARY KEY(day, word_id));
UPDATE words SET familiarity = CASE WHEN state = 'MASTERED' THEN 80 WHEN state = 'LEARNING' THEN 30 ELSE 0 END;
ALTER TABLE ai_usage ADD COLUMN cache_creation_tokens INTEGER NOT NULL DEFAULT 0;
ALTER TABLE ai_usage ADD COLUMN cache_read_tokens INTEGER NOT NULL DEFAULT 0;
ALTER TABLE ai_usage ADD COLUMN total_tokens INTEGER NOT NULL DEFAULT 0;
ALTER TABLE ai_usage ADD COLUMN input_cost REAL NOT NULL DEFAULT 0;
ALTER TABLE ai_usage ADD COLUMN output_cost REAL NOT NULL DEFAULT 0;
ALTER TABLE ai_usage ADD COLUMN cache_creation_cost REAL NOT NULL DEFAULT 0;
ALTER TABLE ai_usage ADD COLUMN cache_read_cost REAL NOT NULL DEFAULT 0;
ALTER TABLE ai_usage ADD COLUMN currency TEXT NOT NULL DEFAULT 'CNY';
ALTER TABLE ai_usage ADD COLUMN usage_known INTEGER NOT NULL DEFAULT 0;
ALTER TABLE ai_usage ADD COLUMN input_price REAL NOT NULL DEFAULT 0;
ALTER TABLE ai_usage ADD COLUMN output_price REAL NOT NULL DEFAULT 0;
ALTER TABLE ai_usage ADD COLUMN cache_creation_price REAL NOT NULL DEFAULT 0;
ALTER TABLE ai_usage ADD COLUMN cache_read_price REAL NOT NULL DEFAULT 0;
ALTER TABLE ai_usage ADD COLUMN price_snapshot INTEGER NOT NULL DEFAULT 0;
UPDATE ai_usage SET total_tokens = MAX(prompt_tokens,0) + MAX(completion_tokens,0), usage_known = CASE WHEN prompt_tokens >= 0 AND completion_tokens >= 0 THEN 1 ELSE 0 END;
ALTER TABLE ai_cache ADD COLUMN usage_id INTEGER;
CREATE TABLE study_session (id INTEGER PRIMARY KEY CHECK(id=1), snapshot TEXT NOT NULL);
CREATE TABLE session_attempts (task_key TEXT PRIMARY KEY, session_id TEXT NOT NULL, word_id INTEGER NOT NULL, created_at INTEGER NOT NULL);
CREATE TABLE auto_ai_jobs (
            id INTEGER PRIMARY KEY AUTOINCREMENT, scope TEXT NOT NULL, word_ids TEXT NOT NULL,
            reason TEXT NOT NULL, status TEXT NOT NULL, model TEXT NOT NULL, currency TEXT NOT NULL,
            reserved_cost REAL NOT NULL, actual_cost REAL, usage_known INTEGER NOT NULL DEFAULT 0,
            usage_id INTEGER, result TEXT NOT NULL DEFAULT '', error TEXT NOT NULL DEFAULT '',
            created_at INTEGER NOT NULL, finished_at INTEGER NOT NULL DEFAULT 0);
CREATE INDEX review_due ON words(next_review_at);
CREATE INDEX event_word ON study_events(word_id, created_at);
CREATE INDEX ai_usage_time ON ai_usage(created_at);
CREATE INDEX auto_ai_time ON auto_ai_jobs(created_at);
