package mapping

import "slices"

// cloneItemProperties owns nested item component data, including NBT arrays.
// Block states are flat, but an item definition cannot use the block shallow copy.
func cloneItemProperties(data map[string]any) map[string]any {
	if data == nil {
		return nil
	}
	out := make(map[string]any, len(data))
	for key, value := range data {
		out[key] = cloneItemValue(value)
	}
	return out
}
func cloneItemValue(value any) any {
	switch v := value.(type) {
	case map[string]any:
		return cloneItemProperties(v)
	case []any:
		out := make([]any, len(v))
		for i, e := range v {
			out[i] = cloneItemValue(e)
		}
		return out
	case []byte:
		return slices.Clone(v)
	case []int32:
		return slices.Clone(v)
	case []int64:
		return slices.Clone(v)
	default:
		return value
	}
}
