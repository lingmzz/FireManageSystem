<script setup>
import { cn } from "cn";

const props = defineProps({
  layout: { type: String, default: "default" },
  class: {
    type: [Boolean, null, String, Object, Array],
    required: false,
    skipCheck: true,
  },
});

const layouts = {
  default: "flex flex-col gap-7 data-[slot=checkbox-group]:gap-3 *:data-[slot=field-group]:gap-4",
  compact: "flex flex-col gap-4",
  grid: "grid grid-cols-1 items-start gap-x-5 gap-y-4 sm:grid-cols-2",
};
</script>

<template>
  <div
    data-slot="field-group"
    :class="
      cn(
        'group/field-group @container/field-group w-full',
        layouts[props.layout],
        props.class,
      )
    "
  >
    <slot />
  </div>
</template>
