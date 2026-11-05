import { describe, expect, it, vi } from 'vitest'
import { ApiError } from './http'
import type { HttpClient, RequestOptions } from './http'
import {
  DEFAULT_LOGIN_REDIRECT,
  LOGIN_URL,
  REMEMBERED_USERNAME_KEY,
  createLoginFlow,
  login,
  loginCopy,
  validateLoginForm,
} from './auth'

// 行为 2.1–2.4（PRD feedback-web-admin §4 任务 2）：注入 fake HttpClient 与 fake session 接缝，
// 不依赖网络与 DOM；流程状态（ref）在 node 环境直接断言

interface PostCall {
  url: string
  body: unknown
  options: RequestOptions | undefined
}

const LOGIN_OK = { token: 'jwt-ok', expiresIn: 604800 }

function makeHttpFake(
  behavior: (call: PostCall) => Promise<unknown> = () => Promise.resolve(LOGIN_OK),
): { http: Pick<HttpClient, 'post'>; calls: PostCall[] } {
  const calls: PostCall[] = []
  const http: Pick<HttpClient, 'post'> = {
    post: (url, body, options) => {
      const call = { url, body, options }
      calls.push(call)
      return behavior(call) as Promise<never>
    },
  }
  return { http, calls }
}

function makeFlow(
  behavior?: (call: PostCall) => Promise<unknown>,
  returnUrl: string | null = null,
) {
  const { http, calls } = makeHttpFake(behavior)
  const setToken = vi.fn()
  const navigate = vi.fn()
  const flow = createLoginFlow({ http, setToken, navigate, returnUrl: () => returnUrl })
  return { flow, calls, setToken, navigate }
}

describe('LoginFormTest', () => {
  describe('validate', () => {
    it('用户名为空 → 提示「请输入用户名」', () => {
      expect(validateLoginForm('', 'secret')).toEqual({ username: '请输入用户名' })
    })

    it('用户名纯空白 → 同样视为未输入（trim 判空）', () => {
      expect(validateLoginForm('   ', 'secret')).toEqual({ username: '请输入用户名' })
    })

    it('密码为空 → 提示「请输入密码」', () => {
      expect(validateLoginForm('admin', '')).toEqual({ password: '请输入密码' })
    })

    it('用户名与密码都为空 → 两条提示都给出', () => {
      expect(validateLoginForm('', '')).toEqual({
        username: '请输入用户名',
        password: '请输入密码',
      })
    })

    it('两者都有值 → 空对象（可提交）', () => {
      expect(validateLoginForm('admin', 'secret')).toEqual({})
    })
  })
})

describe('LoginCopyTest', () => {
  const CASES: Array<[number, string, string]> = [
    [40102, '用户名或密码错误（后端原文）', '用户名或密码错误'],
    [42900, '尝试过于频繁（后端原文）', '尝试过于频繁，请稍后再试'],
    [50000, '服务内部错误（后端原文）', '服务暂不可用，请稍后重试'],
    [40001, 'title: 必填', 'title: 必填'],
    [40100, '请重新登录', '服务暂不可用，请稍后重试'],
    [40401, '反馈不存在', '服务暂不可用，请稍后重试'],
    [99999, '未知码', '服务暂不可用，请稍后重试'],
  ]

  it.each(CASES)('code=%i（后端 message=%s）→ %s', (code, backendMessage, expected) => {
    expect(loginCopy(code, backendMessage)).toBe(expected)
  })
})

describe('LoginFlowTest', () => {
  it('冻结接缝：login() 走 HttpClient.post —— POST /api/login、请求体原样、auth:false（公开接口不带 Bearer）', async () => {
    const { http, calls } = makeHttpFake()
    await expect(login(http, { username: 'admin', password: 'pw' })).resolves.toEqual(LOGIN_OK)
    expect(calls).toHaveLength(1)
    expect(calls[0].url).toBe('/api/login')
    expect(calls[0].body).toEqual({ username: 'admin', password: 'pw' })
    expect(calls[0].options).toEqual({ auth: false })
  })

  it('冻结常量：LOGIN_URL / REMEMBERED_USERNAME_KEY（D12）/ 默认跳转 /feedback（行为 2.3）', () => {
    expect(LOGIN_URL).toBe('/api/login')
    expect(REMEMBERED_USERNAME_KEY).toBe('feedback_admin_username')
    expect(DEFAULT_LOGIN_REDIRECT).toBe('/feedback')
  })

  it('行为 2.3：成功 → session.set(token) → 无 returnUrl 跳默认 /feedback', async () => {
    const { flow, calls, setToken, navigate } = makeFlow()
    await expect(flow.submit({ username: 'admin', password: 'pw' })).resolves.toBe(true)
    expect(calls).toHaveLength(1)
    expect(setToken).toHaveBeenCalledTimes(1)
    expect(setToken).toHaveBeenCalledWith('jwt-ok')
    expect(navigate).toHaveBeenCalledTimes(1)
    expect(navigate).toHaveBeenCalledWith('/feedback')
    expect(flow.error.value).toBeNull()
    expect(flow.submitting.value).toBe(false)
  })

  it('行为 2.3：returnUrl 优先于默认值，原样透传（含筛选 query）', async () => {
    const target = '/feedback?page=2&keyword=%E5%BC%82%E5%B8%B8'
    const { flow, navigate } = makeFlow(undefined, target)
    await expect(flow.submit({ username: 'admin', password: 'pw' })).resolves.toBe(true)
    expect(navigate).toHaveBeenCalledWith(target)
    expect(navigate).not.toHaveBeenCalledWith('/feedback')
  })

  it('行为 2.1：表单不合法 → 本地拦截，不发请求、不 setToken、不跳转', async () => {
    const { flow, calls, setToken, navigate } = makeFlow()
    await expect(flow.submit({ username: '', password: '' })).resolves.toBe(false)
    expect(calls).toHaveLength(0)
    expect(setToken).not.toHaveBeenCalled()
    expect(navigate).not.toHaveBeenCalled()
    expect(flow.fieldErrors.value).toEqual({
      username: '请输入用户名',
      password: '请输入密码',
    })
    expect(flow.submitting.value).toBe(false)
  })

  it('行为 2.1：仅密码为空 → 同样不发请求', async () => {
    const { flow, calls } = makeFlow()
    await expect(flow.submit({ username: 'admin', password: '' })).resolves.toBe(false)
    expect(calls).toHaveLength(0)
    expect(flow.fieldErrors.value).toEqual({ password: '请输入密码' })
  })

  it('本地拦截时清空旧的服务端错误横幅', async () => {
    let fail = true
    const { flow, calls } = makeFlow(() =>
      fail ? Promise.reject(new ApiError(40102, '错')) : Promise.resolve(LOGIN_OK),
    )
    await flow.submit({ username: 'admin', password: 'pw' })
    expect(flow.error.value).toBe('用户名或密码错误')
    await flow.submit({ username: 'admin', password: '' })
    expect(flow.error.value).toBeNull()
    expect(calls).toHaveLength(1)
  })

  const FAILURE_CASES: Array<[number, string]> = [
    [40102, '用户名或密码错误'],
    [42900, '尝试过于频繁，请稍后再试'],
    [50000, '服务暂不可用，请稍后重试'],
  ]

  it.each(FAILURE_CASES)('请求失败 code=%i → 错误文案「%s」，token 与跳转均不发生', async (code, copy) => {
    const { flow, setToken, navigate } = makeFlow(() => Promise.reject(new ApiError(code, '后端原文')))
    await expect(flow.submit({ username: 'admin', password: 'pw' })).resolves.toBe(false)
    expect(flow.error.value).toBe(copy)
    expect(setToken).not.toHaveBeenCalled()
    expect(navigate).not.toHaveBeenCalled()
    expect(flow.submitting.value).toBe(false)
  })

  it('请求失败 code=40001 → 透传后端 message', async () => {
    const { flow } = makeFlow(() => Promise.reject(new ApiError(40001, 'title: 必填')))
    await expect(flow.submit({ username: 'admin', password: 'pw' })).resolves.toBe(false)
    expect(flow.error.value).toBe('title: 必填')
  })

  it('非 ApiError 异常 → 兜底文案（防御分支）', async () => {
    const { flow } = makeFlow(() => Promise.reject(new TypeError('boom')))
    await expect(flow.submit({ username: 'admin', password: 'pw' })).resolves.toBe(false)
    expect(flow.error.value).toBe('服务暂不可用，请稍后重试')
  })

  it('code=0 但 data 缺失（null）→ 兜底文案，不 setToken 不跳转（契约外防御）', async () => {
    const { flow, setToken, navigate } = makeFlow(() => Promise.resolve(null))
    await expect(flow.submit({ username: 'admin', password: 'pw' })).resolves.toBe(false)
    expect(flow.error.value).toBe('服务暂不可用，请稍后重试')
    expect(setToken).not.toHaveBeenCalled()
    expect(navigate).not.toHaveBeenCalled()
  })

  it('失败后再次提交成功 → 旧错误被清空', async () => {
    let fail = true
    const { flow } = makeFlow(() =>
      fail ? Promise.reject(new ApiError(40102, '错')) : Promise.resolve(LOGIN_OK),
    )
    await flow.submit({ username: 'admin', password: 'pw' })
    expect(flow.error.value).toBe('用户名或密码错误')
    fail = false
    await expect(flow.submit({ username: 'admin', password: 'pw' })).resolves.toBe(true)
    expect(flow.error.value).toBeNull()
  })

  describe('submitInFlight', () => {
    it('行为 2.4：请求进行中 submitting=true、重复提交被忽略不再发请求，结束后复位', async () => {
      let release!: (value: unknown) => void
      const gate = new Promise<unknown>((resolve) => {
        release = resolve
      })
      const { flow, calls, setToken, navigate } = makeFlow(() => gate)

      const first = flow.submit({ username: 'admin', password: 'pw' })
      expect(flow.submitting.value).toBe(true)
      expect(calls).toHaveLength(1)

      const second = flow.submit({ username: 'admin', password: 'pw' })
      await expect(second).resolves.toBe(false)
      expect(calls).toHaveLength(1)

      release(LOGIN_OK)
      await expect(first).resolves.toBe(true)
      expect(flow.submitting.value).toBe(false)
      expect(setToken).toHaveBeenCalledTimes(1)
      expect(navigate).toHaveBeenCalledTimes(1)
    })
  })
})
