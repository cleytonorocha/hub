import { reactive } from 'vue'
import { createSearchFilters } from '../utils/taskSearch'

// Kept only for the lifetime of this application tab, including visits to the calendar.
export const taskSearchState = reactive({
  query: '',
  filters: createSearchFilters(),
  appliedQuery: '',
  appliedFilters: createSearchFilters(),
  page: 0,
  size: 50,
  items: [],
  total: 0,
  totalPages: 0,
  categories: [],
  loading: false,
  error: '',
  hasLoaded: false,
  focusTaskId: '',
  scrollY: 0,
})
