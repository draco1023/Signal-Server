-- keys: queue_key [1], queue_metadata_key [2], queue_total_index [3]
-- argv: message [1], current_time [2], sender (possibly null) [3], guid [4], messageId (possibly null) [5]

local messageId

if ARGV[5] ~= nil then
    -- TODO: Remove this branch (and ARGV[5]) once the migration to a clustered message cache is finished
    messageId = tonumber(ARGV[5])
    redis.call("HSET", KEYS[2], "counter", messageId)
else
    messageId = redis.call("HINCRBY", KEYS[2], "counter", 1)
end

redis.call("ZADD", KEYS[1], "NX", messageId, ARGV[1])

if ARGV[3] ~= "nil" then
    redis.call("HSET", KEYS[2], ARGV[3], messageId)
end

redis.call("HSET", KEYS[2], ARGV[4], messageId)

if ARGV[3] ~= "nil" then
    redis.call("HSET", KEYS[2], messageId, ARGV[3])
end

redis.call("HSET", KEYS[2], messageId .. "guid", ARGV[4])

redis.call("EXPIRE", KEYS[1], 7776000)
redis.call("EXPIRE", KEYS[2], 7776000)

redis.call("ZADD", KEYS[3], "NX", ARGV[2], KEYS[1])
return messageId