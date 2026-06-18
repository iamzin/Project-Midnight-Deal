-- KEYS[1]=stock:{pid}  KEYS[2]=buyers:{pid}  ARGV[1]=userId
-- 보상: 구매자였으면 SREM + INCR. return 1=released, 0=noop
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
  redis.call('SREM', KEYS[2], ARGV[1])
  redis.call('INCR', KEYS[1])
  return 1
end
return 0
