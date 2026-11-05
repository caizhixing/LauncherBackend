import { describe, expect, it } from 'vitest'
import viteConfig from '../vite.config'

// 工程引导验收（PRD feedback-web-admin §8）：代理与测试链路就位即通过
describe('web bootstrap', () => {
  it('dev proxy forwards /api to local backend', () => {
    const proxy = (
      viteConfig as {
        server?: { proxy?: Record<string, { target: string }> }
      }
    ).server?.proxy
    expect(proxy?.['/api']?.target).toBe('http://127.0.0.1:8080')
  })
})
