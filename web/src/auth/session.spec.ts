import { afterEach, describe, expect, it } from 'vitest'
import { TOKEN_STORAGE_KEY, session } from './session'

// 行为 1.7（PRD feedback-web-admin §4 任务 1）：vitest node 环境用内存实现替换 localStorage（D1）
class MemoryStorage {
  private readonly map = new Map<string, string>()

  getItem(key: string): string | null {
    return this.map.get(key) ?? null
  }

  setItem(key: string, value: string): void {
    this.map.set(key, value)
  }

  removeItem(key: string): void {
    this.map.delete(key)
  }

  clear(): void {
    this.map.clear()
  }

  key(index: number): string | null {
    return [...this.map.keys()][index] ?? null
  }

  get length(): number {
    return this.map.size
  }
}

const storage = new MemoryStorage()
;(globalThis as { localStorage: Storage }).localStorage = storage

describe('SessionTest', () => {
  afterEach(() => {
    storage.clear()
  })

  it('TOKEN_STORAGE_KEY 冻结为 feedback_admin_token', () => {
    expect(TOKEN_STORAGE_KEY).toBe('feedback_admin_token')
  })

  it('set/get 读写冻结的存储键', () => {
    session.set('jwt-abc')
    expect(localStorage.getItem(TOKEN_STORAGE_KEY)).toBe('jwt-abc')
    expect(session.get()).toBe('jwt-abc')
  })

  it('set 覆盖旧 token', () => {
    session.set('first')
    session.set('second')
    expect(session.get()).toBe('second')
  })

  it('clear 后 get 返回 null', () => {
    session.set('jwt-abc')
    session.clear()
    expect(session.get()).toBeNull()
    expect(localStorage.getItem(TOKEN_STORAGE_KEY)).toBeNull()
  })
})
