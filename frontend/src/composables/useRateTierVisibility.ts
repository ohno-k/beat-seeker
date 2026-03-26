import { ref } from 'vue';
import { API_BASE, TOKEN_KEY } from './constants';

export const showRateTierRef = ref(localStorage.getItem('showRateTier') !== 'false');

function saveToDb(value: boolean) {
  const token = localStorage.getItem(TOKEN_KEY);
  if (token) {
    fetch(`${API_BASE}/api/auth/me/profile`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${token}` },
      body: JSON.stringify({ showRateTier: value })
    }).catch(() => {});
  }
}

export function useRateTierVisibility() {
  const toggleRateTier = () => {
    showRateTierRef.value = !showRateTierRef.value;
    localStorage.setItem('showRateTier', String(showRateTierRef.value));
    saveToDb(showRateTierRef.value);
  };

  const setRateTier = (value: boolean) => {
    showRateTierRef.value = value;
    localStorage.setItem('showRateTier', String(value));
    saveToDb(value);
  };

  return { showRateTier: showRateTierRef, toggleRateTier, setRateTier };
}
