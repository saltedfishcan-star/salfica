<template>
  <!-- 应用壳层：仅承载路由视图 -->
  <main class="app-shell">
    <RouterView />
  </main>
</template>

<style scoped>
.app-shell {
  min-height: 100vh;
}
</style>
