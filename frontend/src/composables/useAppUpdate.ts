import { ref, onUnmounted } from 'vue'

const CHECK_INTERVAL = 5 * 60 * 1000 // 5 minutes

export function useAppUpdate() {
  const hasUpdate = ref(false)
  const currentVersion = __APP_VERSION__

  const check = async () => {
    try {
      const res = await fetch(`/version.json?t=${Date.now()}`)
      if (!res.ok) return
      const data = await res.json()
      if (data.version && data.version !== currentVersion) {
        hasUpdate.value = true
      }
    } catch {
      // network error — ignore
    }
  }

  const timer = setInterval(check, CHECK_INTERVAL)
  onUnmounted(() => clearInterval(timer))

  return { hasUpdate }
}
