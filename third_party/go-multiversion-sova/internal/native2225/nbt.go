package native2225

import (
	"bytes"
	"encoding/json"
	"fmt"
	"reflect"
	"sort"
	"strings"

	"github.com/sandertv/gophertunnel/minecraft/nbt"
)

// Marshal emits deterministic NetworkLittleEndian NBT without converting typed
// component compounds through JSON numbers.
func Marshal(v any) ([]byte, error) { return nbt.Marshal(ordered(reflect.ValueOf(v)).Interface()) }

// MarshalEncoding retains deterministic compounds for consumers whose embedded
// resource format uses ordinary LittleEndian instead of network NBT.
func MarshalEncoding(v any, encoding nbt.Encoding) ([]byte, error) {
	return nbt.MarshalEncoding(ordered(reflect.ValueOf(v)).Interface(), encoding)
}
func orderedMap(v map[string]any) any { return ordered(reflect.ValueOf(v)).Interface() }

func ordered(v reflect.Value) reflect.Value {
	if v.Kind() == reflect.Interface {
		return ordered(v.Elem())
	}
	switch v.Kind() {
	case reflect.Map:
		keys := v.MapKeys()
		sort.Slice(keys, func(i, j int) bool { return keys[i].String() < keys[j].String() })
		fields := make([]reflect.StructField, len(keys))
		values := make([]reflect.Value, len(keys))
		for i, k := range keys {
			x := ordered(v.MapIndex(k))
			values[i] = x
			fields[i] = reflect.StructField{Name: fmt.Sprintf("F%d", i), Type: x.Type(), Tag: reflect.StructTag(fmt.Sprintf("nbt:%q", k.String()))}
		}
		out := reflect.New(reflect.StructOf(fields)).Elem()
		for i, x := range values {
			out.Field(i).Set(x)
		}
		return out
	case reflect.Struct:
		fields := make([]reflect.StructField, v.NumField())
		values := make([]reflect.Value, v.NumField())
		for i := range fields {
			values[i] = ordered(v.Field(i))
			fields[i] = v.Type().Field(i)
			fields[i].Type = values[i].Type()
			// A nonempty map of zero values becomes a zero-valued anonymous
			// struct. Do not let omitempty erase legitimate false/zero states.
			if v.Field(i).Kind() == reflect.Map && v.Field(i).Len() > 0 {
				fields[i].Tag = reflect.StructTag(fmt.Sprintf("nbt:%q", strings.ReplaceAll(fields[i].Tag.Get("nbt"), ",omitempty", "")))
			}
		}
		out := reflect.New(reflect.StructOf(fields)).Elem()
		for i, x := range values {
			out.Field(i).Set(x)
		}
		return out
	case reflect.Slice:
		if v.Type().Elem().Kind() == reflect.Uint8 || v.Type().Elem().Kind() == reflect.Int32 || v.Type().Elem().Kind() == reflect.Int64 {
			return v
		}
		out := make([]any, v.Len())
		for i := range out {
			out[i] = ordered(v.Index(i)).Interface()
		}
		return reflect.ValueOf(out)
	default:
		return v
	}
}

func Decode[T any](data []byte) (T, error) {
	var value T
	buf := bytes.NewBuffer(data)
	err := nbt.NewDecoder(buf).Decode(&value)
	if err == nil && buf.Len() != 0 {
		err = fmt.Errorf("%d unread NBT bytes", buf.Len())
	}
	return value, err
}

func stateKey(state BlockState) string {
	keys := make([]string, 0, len(state.Properties))
	for key := range state.Properties {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	var b strings.Builder
	b.WriteString(state.Name)
	for _, k := range keys {
		x, _ := json.Marshal(state.Properties[k])
		fmt.Fprintf(&b, "\x00%s:%T:%s", k, state.Properties[k], x)
	}
	return b.String()
}
