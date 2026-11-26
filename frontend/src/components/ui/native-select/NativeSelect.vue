<script setup>
import { ChevronDownIcon } from "@lucide/vue";
import { useVModel } from "@vueuse/core";
import { cn } from "cn";

defineOptions({ inheritAttrs: false });

const props = defineProps({
  modelValue: { type: null, required: false },
  class: {
    type: [Boolean, null, String, Object, Array],
    required: false,
    skipCheck: true,
  },
  size: { type: String, required: false },
});

const emit = defineEmits(["update:modelValue"]);

const modelValue = useVModel(props, "modelValue", emit, {
  passive: true,
  defaultValue: "",
});

</script>

<template>
  <div
    :class="
      cn(
        'group/native-select relative w-full min-w-0 has-[select:disabled]:opacity-50',
        props.class,
      )
    "
    data-slot="native-select-wrapper"
    :data-size="props.size ?? 'default'"
  >
    <select
      v-bind="$attrs"
      v-model="modelValue"
      data-slot="native-select"
      class="app-select border-input selection:bg-primary selection:text-primary-foreground focus-visible:border-ring focus-visible:ring-ring/50 aria-invalid:ring-destructive/20 aria-invalid:border-destructive h-9 w-full min-w-0 appearance-none rounded-md border bg-card py-1 pr-8 pl-2.5 text-sm shadow-xs transition-[color,box-shadow] focus-visible:ring-3 aria-invalid:ring-3 data-[size=sm]:h-8 outline-none enabled:cursor-pointer disabled:cursor-not-allowed"
      :data-size="props.size ?? 'default'"
    >
      <slot />
    </select>
    <ChevronDownIcon
      class="text-muted-foreground top-1/2 right-2.5 size-4 -translate-y-1/2 pointer-events-none absolute select-none"
      aria-hidden="true"
      data-slot="native-select-icon"
    />
  </div>
</template>
