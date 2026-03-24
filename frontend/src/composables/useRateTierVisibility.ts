import { ref } from 'vue';

const showRateTier = ref(localStorage.getItem('showRateTier') === 'true');

export function useRateTierVisibility() {
  const toggleRateTier = () => {
    showRateTier.value = !showRateTier.value;
    localStorage.setItem('showRateTier', String(showRateTier.value));
  };

  const setRateTier = (value: boolean) => {
    showRateTier.value = value;
    localStorage.setItem('showRateTier', String(value));
  };

  return { showRateTier, toggleRateTier, setRateTier };
}
