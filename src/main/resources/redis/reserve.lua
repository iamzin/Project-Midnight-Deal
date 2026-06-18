-- KEYS[1]=stock:{pid}  KEYS[2]=buyers:{pid}  ARGV[1]=userId
-- return 1=RESERVED, 0=SOLD_OUT, -1=ALREADY_PURCHASED
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then return -1 end
local n = redis.call('GET', KEYS[1])
if not n or tonumber(n) <= 0 then return 0 end
redis.call('DECR', KEYS[1])
redis.call('SADD', KEYS[2], ARGV[1])
return 1
