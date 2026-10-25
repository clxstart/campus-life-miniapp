USE campus_life;

-- mood 列已被旧版占用（int，表示情绪值），故情绪标签改用 mood_tag
SET @s = (SELECT IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema='campus_life' AND table_name='user_pet' AND column_name='mood_tag')=0,
  'ALTER TABLE user_pet ADD COLUMN mood_tag VARCHAR(16) NOT NULL DEFAULT ''calm''', 'SELECT 1'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
