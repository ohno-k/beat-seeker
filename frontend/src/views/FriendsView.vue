<script setup lang="ts">
import { useRouter } from 'vue-router'
import Friends from '../components/Friends.vue'

const router = useRouter()

const handleViewUser = (friend: { id: number; displayName: string; iidxId: string }) => {
  router.push({ name: 'user-dashboard', params: { userId: friend.id }, query: { name: friend.displayName, mode: 'friend' } })
}
</script>

<template>
  <Friends class="w-full max-w-6xl animate-fade-in" @view-user="handleViewUser" />
</template>
