
talkcan = talkcan or {}
talkcan._operation_yield_prefix = "\0talkcan-operation:"
talkcan.yield_operation = function(label)
  return coroutine.yield(talkcan._operation_yield_prefix .. tostring(label))
end
talkcan._modules = {}
talkcan._loading = {}
talkcan._sources = {}
talkcan._evaluating = 0
local host_spawn = talkcan.host_spawn
local host_defer = talkcan.host_defer
local host_instance_id = talkcan.host_instance_id
local host_acknowledge_spawn_context = talkcan.host_acknowledge_spawn_context
local host_prepare_sleep = talkcan.host_prepare_sleep
local host_audio_describe = talkcan.host_audio_describe
local host_audio_file = talkcan.host_audio_file
local host_log = talkcan.host_log
local host_json_encode = talkcan.host_json_encode
local host_json_decode = talkcan.host_json_decode
local host_opaque_kind = talkcan.host_opaque_kind
local host_work_job_payload = talkcan.host_work_job_payload

-- Install the trusted instruction-count hook before hiding debug. The hook
-- closes over the host watchdog callback, so package code cannot replace it.
local trusted_debug = assert(debug)
local native_coroutine_create = coroutine.create
local native_coroutine_resume = coroutine.resume
local watchdog_tripped = assert(__talkcan_watchdog_tripped)
local hook_interval = assert(__talkcan_hook_interval)
local instruction_budget = assert(__talkcan_instruction_budget)
local instruction_count = 0

local function watchdog_hook()
  instruction_count = instruction_count + hook_interval
  if watchdog_tripped() then error("E_INTERRUPTED", 0) end
  if instruction_count > instruction_budget then error("E_INSTRUCTION_BUDGET", 0) end
end

local function install_hook(thread)
  if thread == nil then
    trusted_debug.sethook(watchdog_hook, "", hook_interval)
  else
    trusted_debug.sethook(thread, watchdog_hook, "", hook_interval)
  end
end

talkcan._reset_instruction_budget = function()
  instruction_count = 0
end
install_hook(nil)

coroutine.create = function(fn)
  if talkcan._evaluating and talkcan._evaluating > 0 then
    error("effect-call-during-load")
  end
  if type(fn) ~= "function" then
    error("bad argument #1 to 'create' (function expected)", 2)
  end
  local thread = native_coroutine_create(fn)
  install_hook(thread)
  return thread
end
coroutine.resume = function(thread, ...)
  local results = table.pack(native_coroutine_resume(thread, ...))
  if not results[1] then
    error("attempt to resume child coroutine", 2)
  end
  return table.unpack(results, 1, results.n)
end
coroutine.wrap = function(fn)
  local thread = coroutine.create(fn)
  return function(...)
    local results = table.pack(native_coroutine_resume(thread, ...))
    if not results[1] then
      error(results[2], 2)
    end
    return table.unpack(results, 2, results.n)
  end
end

-- Trusted host round-trip dispatch helpers. The engine thread runs a callback
-- or entrypoint inside a hooked coroutine through `talkcan._dispatch_call`, and
-- resumes a suspended coroutine through `talkcan._dispatch_resume`. Both return
-- an unambiguous `(kind, coroutine, value)` envelope where kind is one of
-- "completed", "yielded", or "error"; only `value` of a "yielded" envelope that
-- carries the NUL-prefixed operation protocol is accepted by the engine. These
-- closures use the native resume/status so a coroutine error surfaces as an
-- "error" envelope instead of re-raising into the engine. Package code never
-- reaches them: the per-image namespace proxy whitelists a fixed module surface.
local native_coroutine_status = coroutine.status
local hooked_coroutine_create = coroutine.create
local active_dispatch_coroutine = nil
local function dispatch_envelope(co, ok, value)
  if not ok then
    return "error", co, value
  elseif native_coroutine_status(co) == "suspended" then
    return "yielded", co, value
  else
    return "completed", co, value
  end
end
local function dispatch_resume(co, ...)
  active_dispatch_coroutine = co
  local results = table.pack(native_coroutine_resume(co, ...))
  active_dispatch_coroutine = nil
  return dispatch_envelope(co, table.unpack(results, 1, results.n))
end
local function in_active_dispatch()
  local current, is_main = coroutine.running()
  return not is_main and current == active_dispatch_coroutine
end
talkcan._dispatch_call = function(fn, ...)
  local co = hooked_coroutine_create(fn)
  return dispatch_resume(co, ...)
end
talkcan._dispatch_resume = function(co, ok, value)
  return dispatch_resume(co, ok, value)
end

talkcan.module_put = function(name, value)
  name = tostring(name)
  if name == "talkcan" or string.sub(name, 1, 8) == "talkcan." then
    error("E_RESERVED_MODULE")
  end
  talkcan._modules[name] = value
end
talkcan.module_get = function(name)
  return talkcan._modules[tostring(name)]
end
talkcan.module_clear = function(name)
  talkcan._modules[tostring(name)] = nil
end

-- Event constants
talkcan.channel = {
  LIFECYCLE_READY = "ready",
  CAPTURE_COMPLETE = "capture",
  SOS_TRIGGERED = "sos",
}

-- Version constants
talkcan.runtime = {
  LUA_VERSION = "Lua 5.4",
  LUA_RELEASE = "5.4.8",
  API_VERSION = "talkcan-lua-v1",
}
-- Semantic audio operations validate synchronously, register a typed host
-- operation request in the native registry, and yield only its opaque
-- identity. No path, text, JSON, audio token, or delay crosses in the label.
local function transcribe(captured, ...)
  if select(string.char(35), ...) ~= 0 then return nil, { error = "E_INVALID_ARGUMENT" } end
  if talkcan._evaluating and talkcan._evaluating > 0 then error("effect-call-during-load") end
  local ok, request_or_error = talkcan.host_transcribe(captured)
  if not ok then return nil, { error = request_or_error } end
  local success, value = talkcan.yield_operation(request_or_error)
  if success then return { text = value }, nil end
  return nil, { error = value }
end
local function synthesize(params, ...)
  if select(string.char(35), ...) ~= 0 or type(params) ~= "table" then return nil, { error = "E_INVALID_ARGUMENT" } end
  if talkcan._evaluating and talkcan._evaluating > 0 then error("effect-call-during-load") end
  local ok, request_or_error = talkcan.host_synthesize(params)
  if not ok then return nil, { error = request_or_error } end
  local success, value = talkcan.yield_operation(request_or_error)
  if success then return value, nil end
  return nil, { error = value }
end
talkcan.transcription = { transcribe = transcribe }
talkcan.synthesis = { synthesize = synthesize }
local function schedule(audio, options, ...)
  if select(string.char(35), ...) ~= 0 or type(audio) ~= "userdata" or type(options) ~= "table" then return nil, { error = "E_INVALID_ARGUMENT" } end
  if talkcan._evaluating and talkcan._evaluating > 0 then error("effect-call-during-load") end
  local ok, request_or_error = talkcan.host_playback(audio, options)
  if not ok then return nil, { error = request_or_error } end
  local success, value = talkcan.yield_operation(request_or_error)
  if success then return { status = "scheduled" }, nil end
  return nil, { error = value }
end
talkcan.playback = { schedule = schedule }

local function audio_file_yield(kind, args)
  if talkcan._evaluating and talkcan._evaluating > 0 then error("effect-call-during-load") end
  local ok, request_or_error = host_audio_file(kind, args)
  if not ok then return nil, { error = request_or_error } end
  local success, value = talkcan.yield_operation(request_or_error)
  if success then return value, nil end
  return nil, { error = value }
end
talkcan.audio = {
  describe = function(recording, ...)
    if select(string.char(35), ...) ~= 0 or type(recording) ~= "userdata" then return nil, { error = "E_INVALID_ARGUMENT" } end
    if talkcan._evaluating and talkcan._evaluating > 0 then error("effect-call-during-load") end
    local ok, value = host_audio_describe(recording)
    if not ok then return nil, { error = value } end
    return value, nil
  end,
  open = function(mount, path, options, ...)
    if select(string.char(35), ...) ~= 0 or type(mount) ~= "userdata" or type(path) ~= "string" or type(options) ~= "table" then return nil, { error = "E_INVALID_ARGUMENT" } end
    for key in pairs(options) do if key ~= "format" then return nil, { error = "E_INVALID_ARGUMENT" } end end
    if options.format ~= "wav-pcm-s16le" then return nil, { error = "E_INVALID_ARGUMENT" } end
    return audio_file_yield("open", { mount = mount, path = path, format = options.format })
  end,
  export = function(recording, mount, path, options, ...)
    if select(string.char(35), ...) ~= 0 or type(recording) ~= "userdata" or type(mount) ~= "userdata" or type(path) ~= "string" or type(options) ~= "table" then return nil, { error = "E_INVALID_ARGUMENT" } end
    for key in pairs(options) do if key ~= "format" and key ~= "mode" then return nil, { error = "E_INVALID_ARGUMENT" } end end
    if options.format ~= "wav-pcm-s16le" then return nil, { error = "E_INVALID_ARGUMENT" } end
    local mode = options.mode or "create-new"
    if mode ~= "create-new" and mode ~= "replace" then return nil, { error = "E_INVALID_ARGUMENT" } end
    return audio_file_yield("export", { recording = recording, mount = mount, path = path, format = options.format, mode = mode })
  end,
}

-- Filesystem operations. `mount` is a bounded synchronous lookup returning an
-- opaque generation-owned handle. Every I/O function validates, registers a
-- typed host-operation request, and yields only its opaque identity.
local function fs_yield(kind, mount, path, extra)
  if talkcan._evaluating and talkcan._evaluating > 0 then error("effect-call-during-load") end
  if type(mount) ~= "userdata" or type(path) ~= "string" then return nil, { error = "E_INVALID_ARGUMENT" } end
  local args = { mount = mount, path = path }
  if extra then for k, v in pairs(extra) do args[k] = v end end
  local ok, request_or_error = talkcan.host_fs_io(kind, args)
  if not ok then return nil, { error = request_or_error } end
  local success, value = talkcan.yield_operation(request_or_error)
  if success then return value, nil end
  return nil, { error = value }
end
talkcan.fs = {
  mount = function(id, ...)
    if select(string.char(35), ...) ~= 0 then return nil, { error = "E_INVALID_ARGUMENT" } end
    if talkcan._evaluating and talkcan._evaluating > 0 then error("effect-call-during-load") end
    local ok, result = talkcan.host_fs_mount(id)
    if not ok then return nil, { error = result } end
    return result, nil
  end,
  mkdir = function(mount, path, options, ...)
    if select(string.char(35), ...) ~= 0 or type(options) ~= "table" then return nil, { error = "E_INVALID_ARGUMENT" } end
    for k in pairs(options) do if k ~= "parents" then return nil, { error = "E_INVALID_ARGUMENT" } end end
    if type(options.parents) ~= "boolean" then return nil, { error = "E_INVALID_ARGUMENT" } end
    return fs_yield("mkdir", mount, path, { parents = options.parents })
  end,
  stat = function(mount, path, ...)
    if select(string.char(35), ...) ~= 0 then return nil, { error = "E_INVALID_ARGUMENT" } end
    return fs_yield("stat", mount, path, nil)
  end,
  list = function(mount, path, options, ...)
    if select(string.char(35), ...) ~= 0 then return nil, { error = "E_INVALID_ARGUMENT" } end
    local extra = {}
    if options ~= nil then
      if type(options) ~= "table" then return nil, { error = "E_INVALID_ARGUMENT" } end
      for k in pairs(options) do
        if k ~= "limit" and k ~= "cursor" then return nil, { error = "E_INVALID_ARGUMENT" } end
      end
      if options.limit ~= nil then extra.limit = options.limit end
      if options.cursor ~= nil then extra.cursor = options.cursor end
    end
    return fs_yield("list", mount, path, extra)
  end,
  read_text = function(mount, path, options, ...)
    if select(string.char(35), ...) ~= 0 or type(options) ~= "table" then return nil, { error = "E_INVALID_ARGUMENT" } end
    for k in pairs(options) do if k ~= "max_bytes" then return nil, { error = "E_INVALID_ARGUMENT" } end end
    if type(options.max_bytes) ~= "number" then return nil, { error = "E_INVALID_ARGUMENT" } end
    return fs_yield("read_text", mount, path, { max_bytes = options.max_bytes })
  end,
  write_text = function(mount, path, text, options, ...)
    if select(string.char(35), ...) ~= 0 or type(text) ~= "string" or type(options) ~= "table" then return nil, { error = "E_INVALID_ARGUMENT" } end
    for k in pairs(options) do if k ~= "mode" then return nil, { error = "E_INVALID_ARGUMENT" } end end
    if type(options.mode) ~= "string" then return nil, { error = "E_INVALID_ARGUMENT" } end
    return fs_yield("write_text", mount, path, { text = text, mode = options.mode })
  end,
  remove = function(mount, path, options, ...)
    if select(string.char(35), ...) ~= 0 then return nil, { error = "E_INVALID_ARGUMENT" } end
    local extra = {}
    if options ~= nil then
      if type(options) ~= "table" then return nil, { error = "E_INVALID_ARGUMENT" } end
      for k in pairs(options) do if k ~= "missing_ok" then return nil, { error = "E_INVALID_ARGUMENT" } end end
      if options.missing_ok ~= nil then extra.missing_ok = options.missing_ok end
    end
    return fs_yield("remove", mount, path, extra)
  end,
}

-- Keyboard output operations validate synchronously, register a typed
-- host-operation request in the native registry, and yield only its opaque
-- identity. No text, profile, key, JSON, or transport data crosses in the
-- label; the host obtains the bounded payload only through exactly-once
-- typed claim.
local function keyboard_output_yield(kind, request)
  if talkcan._evaluating and talkcan._evaluating > 0 then error("effect-call-during-load") end
  local ok, request_or_error = talkcan.host_keyboard_output(kind, request)
  if not ok then return nil, { error = request_or_error } end
  local success, value = talkcan.yield_operation(request_or_error)
  if success then return value, nil end
  return nil, { error = value }
end
talkcan.keyboard_output = {
  send_text = function(request, ...)
    if select(string.char(35), ...) ~= 0 or type(request) ~= "table" then return nil, { error = "E_INVALID_ARGUMENT" } end
    return keyboard_output_yield("send_text", request)
  end,
  send_key = function(request, ...)
    if select(string.char(35), ...) ~= 0 or type(request) ~= "table" then return nil, { error = "E_INVALID_ARGUMENT" } end
    return keyboard_output_yield("send_key", request)
  end,
}

-- Package-local profile resolution. Synchronous detached lookup over the
-- host-installed grant snapshot; returns `{id,type,name,values,secrets}` with
-- opaque secret references. Never yields; missing/foreign ids deny without
-- revealing existence. Source evaluation denies resolution without failing the
-- module load (this is not a host effect).
talkcan.profiles = {
  get = function(profile_id, ...)
    if select(string.char(35), ...) ~= 0 then return nil, { error = "E_INVALID_ARGUMENT" } end
    if talkcan._evaluating and talkcan._evaluating > 0 then return nil, { error = "E_INVALID_CONTEXT" } end
    local ok, result = talkcan.host_profiles_get(profile_id)
    if not ok then return nil, { error = result } end
    return result, nil
  end,
}

-- Protected secret resolution. Validates synchronously, registers a typed
-- SECRET_READ host-operation request, and yields only its opaque identity.
-- Only a current opaque reference resolves; strings, guessed aliases, and
-- foreign references are rejected before any protected access.
local function secrets_read(reference, ...)
  if select(string.char(35), ...) ~= 0 then return nil, { error = "E_INVALID_ARGUMENT" } end
  if talkcan._evaluating and talkcan._evaluating > 0 then error("effect-call-during-load") end
  local ok, request_or_error = talkcan.host_secrets_read(reference)
  if not ok then return nil, { error = request_or_error } end
  local success, value = talkcan.yield_operation(request_or_error)
  if success then return value, nil end
  return nil, { error = value }
end
talkcan.secrets = { read = secrets_read }

-- Generic HTTPS request. Validates synchronously, registers a typed
-- HTTP_REQUEST host-operation request, and yields only its opaque identity.
-- Resumes with a complete bounded `{status,headers,body}` table; no URL,
-- header, or body crosses in the yielded label.
local function http_request(request, ...)
  if select(string.char(35), ...) ~= 0 or type(request) ~= "table" then return nil, { error = "E_INVALID_ARGUMENT" } end
  if talkcan._evaluating and talkcan._evaluating > 0 then error("effect-call-during-load") end
  local ok, request_or_error = talkcan.host_http_request(request)
  if not ok then return nil, { error = request_or_error } end
  local success, value = talkcan.yield_operation(request_or_error)
  if success then return value, nil end
  return nil, { error = value }
end
talkcan.http = { request = http_request }

-- Durable work module. `open` is a bounded synchronous lookup returning an
-- opaque generation-owned Queue handle. Queue/Job methods are defined on the
-- native userdata types; the module only provides the entry point.
talkcan.work = {
  open = function(queue_id, ...)
    if select(string.char(35), ...) ~= 0 then return nil, { error = "E_INVALID_ARGUMENT" } end
    if type(queue_id) ~= "string" then return nil, { error = "E_INVALID_ARGUMENT" } end
    if talkcan._evaluating and talkcan._evaluating > 0 then return nil, { error = "E_INVALID_CONTEXT" } end
    local ok, result = talkcan.host_work_open(queue_id)
    if not ok then return nil, { error = result } end
    return result, nil
  end,
}

-- Queue/Job method tables routed through __index on the native userdata.
-- These are Lua functions (not host callbacks) so they can yield across
-- the coroutine boundary without hitting a C-call restriction.
talkcan._queue_methods = {
  submit = function(self, payload, ...)
    if select(string.char(35), ...) ~= 0 then return nil, { error = "E_INVALID_ARGUMENT" } end
    if talkcan._evaluating and talkcan._evaluating > 0 then error("effect-call-during-load") end
    local ok, request_or_error = talkcan.host_work_submit(self, payload)
    if not ok then return nil, { error = request_or_error } end
    local success, value = talkcan.yield_operation(request_or_error)
    if success then return true, nil end
    return nil, { error = value }
  end,
  receive = function(self, ...)
    if select(string.char(35), ...) ~= 0 then return nil, { error = "E_INVALID_ARGUMENT" } end
    if talkcan._evaluating and talkcan._evaluating > 0 then error("effect-call-during-load") end
    local ok, request_or_error = talkcan.host_work_receive(self)
    if not ok then return nil, { error = request_or_error } end
    local success, value = talkcan.yield_operation(request_or_error)
    if success then return value, nil end
    return nil, { error = value }
  end,
}
talkcan._job_methods = {
  payload = function(self, ...)
    if select(string.char(35), ...) ~= 0 then return nil, { error = "E_INVALID_ARGUMENT" } end
    return host_work_job_payload(self)
  end,
  effect = function(self, key, fn, ...)
    if select(string.char(35), ...) ~= 0 then return nil, { error = "E_INVALID_ARGUMENT" } end
    if type(key) ~= "string" or type(fn) ~= "function" then
      return nil, { error = "E_INVALID_ARGUMENT" }
    end
    if talkcan._evaluating and talkcan._evaluating > 0 then error("effect-call-during-load") end
    -- The public API is Job:effect(key, function). Package/config replacement
    -- retires the work epoch, so the canonical key is also the stable
    -- host-internal fingerprint for same-revision replay.
    local ok, request_or_error = talkcan.host_work_effect_begin(self, key, key)
    if not ok then return nil, { error = request_or_error } end
    local success, begin_value = talkcan.yield_operation(request_or_error)
    if not success then return nil, { error = begin_value } end
    -- begin_value is {replay=bool, result_ok=bool, result=<decoded Lua value>?}
    if type(begin_value) == "table" and begin_value.replay then
      if begin_value.result_ok == false then
        return nil, begin_value.result
      end
      return begin_value.result, nil
    end
    -- Phase 2: run the effect function and preserve its exact normalized
    -- (value, nil) or (nil, error_table) result.
    local call_ok, call_value, call_error = pcall(fn)
    local result_ok = call_ok and call_error == nil
    local effect_value
    local return_error
    if result_ok then
      effect_value = call_value
    elseif call_ok then
      effect_value = call_error
      return_error = call_error
    else
      effect_value = { error = "E_EFFECT_ERROR" }
      return_error = effect_value
    end
    -- Phase 3: commit effect (host encodes the raw Lua value)
    local ok2, request_or_error2 = talkcan.host_work_effect_commit(self, key, result_ok, effect_value)
    if not ok2 then return nil, { error = request_or_error2 } end
    local success2, value2 = talkcan.yield_operation(request_or_error2)
    if not success2 then return nil, { error = value2 } end
    if result_ok then
      return call_value, nil
    else
      return nil, return_error
    end
  end,
  complete = function(self, result, ...)
    if select(string.char(35), ...) ~= 0 then return nil, { error = "E_INVALID_ARGUMENT" } end
    if talkcan._evaluating and talkcan._evaluating > 0 then error("effect-call-during-load") end
    local ok, request_or_error = talkcan.host_work_complete(self, result)
    if not ok then return nil, { error = request_or_error } end
    local success, value = talkcan.yield_operation(request_or_error)
    if success then return true, nil end
    return nil, { error = value }
  end,
  fail = function(self, reason, ...)
    if select(string.char(35), ...) ~= 0 then return nil, { error = "E_INVALID_ARGUMENT" } end
    if talkcan._evaluating and talkcan._evaluating > 0 then error("effect-call-during-load") end
    local ok, request_or_error = talkcan.host_work_fail(self, reason)
    if not ok then return nil, { error = request_or_error } end
    local success, value = talkcan.yield_operation(request_or_error)
    if success then return true, nil end
    return nil, { error = value }
  end,
}

talkcan._opaque_metatable = {
  __index = function(value, key)
    local kind = host_opaque_kind(value)
    if kind == "queue" then return talkcan._queue_methods[key] end
    if kind == "job" then return talkcan._job_methods[key] end
    error("opaque value has no readable properties")
  end,
  __newindex = function() error("opaque value is read-only") end,
  __metatable = false,
  __tostring = function(value)
    local kind = host_opaque_kind(value)
    if kind == "audio" then return "opaque_audio" end
    if kind == "queue" then return "opaque_queue" end
    if kind == "job" then return "opaque_job" end
    if kind == "mount" then return "opaque_mount" end
    if kind == "secret" then return "opaque_secret_reference" end
    if kind == "json_null" then return "null" end
    return "opaque_value"
  end,
}

talkcan.json = {
  encode = function(value, ...)
    if select(string.char(35), ...) ~= 0 then return nil, "E_INVALID_ARGUMENT" end
    return host_json_encode(value)
  end,
  decode = function(value, ...)
    if select(string.char(35), ...) ~= 0 or type(value) ~= "string" then
      return nil, "E_INVALID_ARGUMENT"
    end
    return host_json_decode(value)
  end,
}
talkcan.json.null = assert(host_json_decode("null"))

-- Preloaded host modules and already-opened safe Lua standard libraries.
talkcan._preloaded = {
  ["coroutine"] = coroutine,
  ["math"] = math,
  ["string"] = string,
  ["table"] = table,
  ["utf8"] = utf8,
  ["talkcan.runtime"] = talkcan.runtime,
  ["talkcan.channel"] = talkcan.channel,
  ["talkcan.log"] = talkcan.log,
  ["talkcan.transcription"] = talkcan.transcription,
  ["talkcan.synthesis"] = talkcan.synthesis,
  ["talkcan.playback"] = talkcan.playback,
  ["talkcan.fs"] = talkcan.fs,
  ["talkcan.audio"] = talkcan.audio,
  ["talkcan.keyboard_output"] = talkcan.keyboard_output,
  ["talkcan.profiles"] = talkcan.profiles,
  ["talkcan.secrets"] = talkcan.secrets,
  ["talkcan.http"] = talkcan.http,
  ["talkcan.json"] = talkcan.json,
  ["talkcan.work"] = talkcan.work,
}

talkcan.runtime.spawn = function(fn, ...)
  if talkcan._evaluating and talkcan._evaluating > 0 then
    error("effect-call-during-load")
  end
  if select(string.char(35), ...) ~= 0 or type(fn) ~= "function" then
    return nil, { error = "E_INVALID_ARGUMENT" }
  end
  if not in_active_dispatch() then
    return nil, { error = "E_INVALID_CONTEXT" }
  end

  -- Authorization is deliberately host-owned: host_spawn verifies the active
  -- dispatch context and coroutine identity. Lua-visible globals cannot
  -- grant a plugin-created thread or synchronous callback this capability.
  local ok, res = host_spawn(fn)
  if not ok then
    if res == "E_INVALID_CONTEXT" then
      local observed = false
      local err = setmetatable({}, {
        __index = function(_, key)
          if key == "error" then
            if not observed then
              observed = true
              host_acknowledge_spawn_context()
            end
            return res
          end
        end,
        __metatable = false,
      })
      return nil, err
    end
    return nil, { error = res }
  end
  return true, nil
end

talkcan.runtime.defer = function(fn, ...)
  if talkcan._evaluating and talkcan._evaluating > 0 then
    error("effect-call-during-load")
  end
  if select(string.char(35), ...) ~= 0 or type(fn) ~= "function" then
    return nil, { error = "E_INVALID_ARGUMENT" }
  end
  if not in_active_dispatch() then
    return nil, { error = "E_INVALID_CONTEXT" }
  end
  local ok, res = host_defer(fn)
  if not ok then
    return nil, { error = res }
  end
  return true, nil
end

talkcan.runtime.sleep = function(seconds, ...)
  if talkcan._evaluating and talkcan._evaluating > 0 then
    error("effect-call-during-load")
  end
  if select(string.char(35), ...) ~= 0 or type(seconds) ~= "number" or seconds ~= seconds or seconds == math.huge or seconds == -math.huge or seconds < 0 or seconds > 86400 then
    return nil, { error = "E_INVALID_ARGUMENT" }
  end
  if not in_active_dispatch() then
    return nil, { error = "E_INVALID_CONTEXT" }
  end
  local ok, res = host_prepare_sleep(seconds)
  if not ok then
    return nil, { error = res }
  end
  local success, value = coroutine.yield(talkcan._operation_yield_prefix .. "sleep:" .. tostring(res))
  if success then
    return true, nil
  else
    return nil, { error = value }
  end
end

setmetatable(talkcan.runtime, {
  __index = function(_, key)
    if key == "INSTANCE_ID" then return host_instance_id() end
    return nil
  end,
  __newindex = function(table_value, key, value)
    if key == "INSTANCE_ID" then error("INSTANCE_ID is immutable", 2) end
    rawset(table_value, key, value)
  end,
  __metatable = false,
})

local function make_log_fn(level)
  return function(payload)
    if talkcan._evaluating and talkcan._evaluating > 0 then
      error("effect-call-during-load")
    end
    if type(payload) ~= "table" then
      return nil, { error = "E_INVALID_VALUE" }
    end
    return host_log(level, payload)
  end
end

talkcan.log = {
  debug = make_log_fn("debug"),
  info = make_log_fn("info"),
  warn = make_log_fn("warn"),
  error = make_log_fn("error"),
}

-- Each program image receives a private, read-only view of host capabilities.
-- Host closures remain in private backing tables; plugin writes never reach the
-- shared namespace or its reserved module tables.
function talkcan._new_image_namespace(sources, modules, image_env)
  local host = talkcan
  local function readonly(backing)
    return setmetatable({}, {
      __index = backing,
      __pairs = function() return pairs(backing) end,
      __newindex = function() error("attempt to modify read-only talkcan namespace", 2) end,
      __metatable = false,
    })
  end
  local runtime, channel, log, transcription, synthesis, playback, audio, fs, keyboard_output, json, profiles, secrets, http, work = {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}
  for key, value in pairs(host.runtime) do runtime[key] = value end
  runtime.INSTANCE_ID = host.runtime.INSTANCE_ID
  for key, value in pairs(host.channel) do channel[key] = value end
  for key, value in pairs(host.log) do log[key] = value end
  for key, value in pairs(host.transcription) do transcription[key] = value end
  for key, value in pairs(host.synthesis) do synthesis[key] = value end
  for key, value in pairs(host.playback) do playback[key] = value end
  for key, value in pairs(host.audio) do audio[key] = value end
  for key, value in pairs(host.fs) do fs[key] = value end
  for key, value in pairs(host.keyboard_output) do keyboard_output[key] = value end
  for key, value in pairs(host.json or {}) do json[key] = value end
  for key, value in pairs(host.profiles) do profiles[key] = value end
  for key, value in pairs(host.secrets) do secrets[key] = value end
  for key, value in pairs(host.http) do http[key] = value end
  for key, value in pairs(host.work) do work[key] = value end
  local private = {
    _sources = sources,
    _modules = modules,
    _loading = {},
    _image_env = image_env,
    _operation_yield_prefix = host._operation_yield_prefix,
    runtime = readonly(runtime),
    channel = readonly(channel),
    log = readonly(log),
    transcription = readonly(transcription),
    synthesis = readonly(synthesis),
    playback = readonly(playback),
    audio = readonly(audio),
    fs = readonly(fs),
    keyboard_output = readonly(keyboard_output),
    profiles = readonly(profiles),
    secrets = readonly(secrets),
    http = readonly(http),
    json = readonly(json),
    work = readonly(work),
  }
  private.module_put = function(name, value)
    name = tostring(name)
    if name == "talkcan" or string.sub(name, 1, 8) == "talkcan." then
      error("E_RESERVED_MODULE")
    end
    modules[name] = value
  end
  private.module_get = function(name) return modules[tostring(name)] end
  private.module_clear = function(name) modules[tostring(name)] = nil end
  private.yield_operation = function(label)
    return coroutine.yield(private._operation_yield_prefix .. tostring(label))
  end
  private._preloaded = {
    ["coroutine"] = coroutine,
    ["math"] = math,
    ["string"] = string,
    ["table"] = table,
    ["utf8"] = utf8,
    ["talkcan.runtime"] = private.runtime,
    ["talkcan.channel"] = private.channel,
    ["talkcan.log"] = private.log,
    ["talkcan.transcription"] = private.transcription,
    ["talkcan.synthesis"] = private.synthesis,
    ["talkcan.playback"] = private.playback,
    ["talkcan.audio"] = private.audio,
    ["talkcan.fs"] = private.fs,
    ["talkcan.keyboard_output"] = private.keyboard_output,
    ["talkcan.profiles"] = private.profiles,
    ["talkcan.secrets"] = private.secrets,
    ["talkcan.http"] = private.http,
    ["talkcan.json"] = private.json,
    ["talkcan.work"] = private.work,
  }
  local proxy = readonly(private)
  return proxy
end

-- Preserve `load` for package-local source compilation, but never expose the
-- base loader's binary mode to plugin code.
local native_load = load
load = function(chunk, source, mode, env)
  if type(chunk) == "string" and string.byte(chunk, 1) == 27 then
    return nil, "binary Lua chunks are not accepted"
  end
  if mode ~= nil and mode ~= "t" then
    return nil, "binary Lua chunks are not accepted"
  end
  return native_load(chunk, source, "t", env or _G)
end

-- Custom require replacement
function require(name)
  if type(name) ~= "string" then
    error("E_INVALID_MODULE_NAME")
  end
  if name == "" then
    error("E_INVALID_MODULE_NAME")
  end
  for segment in string.gmatch(name .. ".", "([^%.]*)%.") do
    if segment == "" then
      error("E_INVALID_MODULE_NAME")
    end
    if not string.match(segment, "^[a-z][a-z0-9_]*$") then
      error("E_INVALID_MODULE_NAME")
    end
  end

  local is_reserved = false
  if name == "talkcan" or string.sub(name, 1, 8) == "talkcan." then
    is_reserved = true
  end

  local image_env = talkcan._image_env or _G
  local image_talkcan = rawget(image_env, "talkcan") or talkcan
  local preloaded = image_talkcan._preloaded[name]
  if preloaded ~= nil then
    return preloaded
  end
  if is_reserved then
    error("E_RESERVED_MODULE")
  end

  local cached = image_talkcan._modules[name]
  if cached ~= nil then
    return cached
  end

  if image_talkcan._loading[name] then
    error("E_MODULE_CYCLE")
  end
  local source = image_talkcan._sources[name]
  if not source then
    error("E_MODULE_NOT_FOUND")
  end

  image_talkcan._loading[name] = true
  local chunk, err = native_load(source, "@" .. name, "t", image_env)
  if not chunk then
    image_talkcan._loading[name] = nil
    error(err)
  end

  talkcan._evaluating = (talkcan._evaluating or 0) + 1
  local success, result = pcall(chunk)
  talkcan._evaluating = talkcan._evaluating - 1
  image_talkcan._loading[name] = nil

  if not success then
    error(result)
  end

  if result == nil then
    result = true
  end
  image_talkcan._modules[name] = result

  return result
end

-- Build one isolated source-only package image and return its callback table.
-- Every module shares one private environment/cache, while host capabilities
-- remain read-only views backed by trusted closures.
function talkcan._install_image(entry, sources)
  if type(entry) ~= "string" or type(sources) ~= "table" then
    error("E_INVALID_ENTRYPOINT")
  end
  local modules, loading = {}, {}
  local env = {}
  local image_talkcan

  local function image_require(name)
    if type(name) ~= "string" or name == "" then error("E_INVALID_MODULE_NAME") end
    for segment in string.gmatch(name .. ".", "([^%.]*)%.") do
      if segment == "" or not string.match(segment, "^[a-z][a-z0-9_]*$") then
        error("E_INVALID_MODULE_NAME")
      end
    end
    local preloaded = image_talkcan._preloaded[name]
    if preloaded ~= nil then return preloaded end
    if name == "talkcan" or string.sub(name, 1, 8) == "talkcan." then
      error("E_RESERVED_MODULE")
    end
    if modules[name] ~= nil then return modules[name] end
    if loading[name] then error("E_MODULE_CYCLE") end
    local source = sources[name]
    if type(source) ~= "string" then error("E_MODULE_NOT_FOUND") end
    loading[name] = true
    local chunk, load_error = native_load(source, "@" .. name, "t", env)
    if not chunk then
      loading[name] = nil
      error(load_error)
    end
    talkcan._evaluating = (talkcan._evaluating or 0) + 1
    local ok, result = pcall(chunk)
    talkcan._evaluating = talkcan._evaluating - 1
    loading[name] = nil
    if not ok then error(result) end
    if result == nil then result = true end
    modules[name] = result
    return result
  end

  for _, name in ipairs({
    "assert", "error", "getmetatable", "ipairs", "next", "pairs", "pcall",
    "rawequal", "rawget", "rawlen", "rawset", "select", "setmetatable",
    "tonumber", "tostring", "type", "warn", "xpcall",
    "coroutine", "math", "string", "table", "utf8",
  }) do
    env[name] = _G[name]
  end
  env.require = image_require
  env._G = env
  image_talkcan = talkcan._new_image_namespace(sources, modules, env)
  env.talkcan = image_talkcan
  local result = image_require(entry)
  if type(result) ~= "table" then error("E_INVALID_ENTRYPOINT") end
  return result
end

-- Source-only mode: disable base loadfile/dofile
loadfile = nil
dofile = nil
-- Bootstrap wrappers close over native callbacks; plugins cannot call them.
talkcan.host_spawn = nil
talkcan.host_defer = nil
talkcan.host_acknowledge_spawn_context = nil
talkcan.host_audio_describe = nil
talkcan.host_audio_file = nil
talkcan.host_instance_id = nil
talkcan.host_prepare_sleep = nil
talkcan.host_log = nil

-- Package code receives only the safe libraries captured above.
if string then string.dump = function() error("string.dump is disabled") end end
debug = nil
io = nil
os = nil
package = nil
__talkcan_watchdog_tripped = nil
__talkcan_hook_interval = nil
__talkcan_instruction_budget = nil
