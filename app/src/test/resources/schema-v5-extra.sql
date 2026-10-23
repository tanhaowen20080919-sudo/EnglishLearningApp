CREATE TABLE word_memory (word_id INTEGER PRIMARY KEY, mastery REAL NOT NULL, stability REAL NOT NULL, difficulty REAL NOT NULL, lapses INTEGER NOT NULL, streak INTEGER NOT NULL, recent REAL NOT NULL, forms TEXT NOT NULL, contexts TEXT NOT NULL, last_at INTEGER NOT NULL);
INSERT INTO word_memory SELECT id, familiarity / 100.0, MAX(0.6,(next_review_at-last_review_at)/86400000.0),0.5,mistake_count,streak,CASE WHEN review_count>0 THEN MIN(1.0,correct_count*1.0/review_count) ELSE 0.5 END,'[]','[]',last_review_at FROM words WHERE state!='NEW';
CREATE TABLE learning_days (day TEXT PRIMARY KEY, snapshot TEXT NOT NULL, mode TEXT NOT NULL, planned INTEGER NOT NULL, completed INTEGER NOT NULL DEFAULT 0, active_ms INTEGER NOT NULL DEFAULT 0);
CREATE TABLE diagnostic_attempts (task_key TEXT PRIMARY KEY, day TEXT NOT NULL, phase TEXT NOT NULL, form TEXT NOT NULL, tag TEXT NOT NULL, word_id INTEGER NOT NULL, correct INTEGER NOT NULL, elapsed_ms INTEGER NOT NULL, context TEXT NOT NULL, created_at INTEGER NOT NULL, error_reason TEXT NOT NULL DEFAULT '');
CREATE INDEX diagnostic_day ON diagnostic_attempts(day,form,tag);
CREATE TABLE reading_words (day TEXT NOT NULL, reading_id TEXT NOT NULL, word TEXT NOT NULL, meaning TEXT NOT NULL, impacts INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(day,reading_id,word));
CREATE TABLE ai_tasks (task_key TEXT PRIMARY KEY, content_type TEXT NOT NULL, input_hash TEXT NOT NULL, model TEXT NOT NULL, version INTEGER NOT NULL, created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, status TEXT NOT NULL, content TEXT NOT NULL DEFAULT '', error TEXT NOT NULL DEFAULT '', usage_id INTEGER);
ALTER TABLE ai_usage ADD COLUMN elapsed_ms INTEGER NOT NULL DEFAULT 0;
ALTER TABLE ai_usage ADD COLUMN request_status TEXT NOT NULL DEFAULT 'OK';
