<script setup>
const props = defineProps({
  class: {
    type: [Boolean, null, String, Object, Array],
    required: false,
    skipCheck: true,
  },
});
</script>

<template>
  <option
    data-slot="native-select-option"
    :class="props.class"
  >
    <slot />
  </option>
</template>
