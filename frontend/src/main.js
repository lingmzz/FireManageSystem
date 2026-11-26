import { createApp } from 'vue'
import '@fontsource-variable/geist'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Empty, EmptyHeader, EmptyTitle } from '@/components/ui/empty'
import { Field, FieldGroup, FieldLabel, FieldLegend, FieldSet } from '@/components/ui/field'
import { Input } from '@/components/ui/input'
import { NativeSelect, NativeSelectOption } from '@/components/ui/native-select'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { Textarea } from '@/components/ui/textarea'
import DateInput from './components/DateInput.vue'
import FormCheckbox from './components/FormCheckbox.vue'
import FormField from './components/FormField.vue'
import ValidatedForm from './components/ValidatedForm.vue'
import App from './App.vue'
import './style.css'

const app = createApp(App)
const interfaceComponents = {
  Alert, AlertDescription, Badge, Button, Card, CardContent, Checkbox: FormCheckbox, DateInput,
  Empty, EmptyHeader, EmptyTitle, Field, FieldGroup, FieldLabel, FieldLegend, FieldSet, FormField,
  Input, NativeSelect, NativeSelectOption, Table, TableBody, TableCell, TableHead,
  TableHeader, TableRow, Tabs, TabsList, TabsTrigger, Textarea, ValidatedForm
}

for (const [name, component] of Object.entries(interfaceComponents)) {
  app.component('Ui' + name, component)
}

app.mount('#app')
