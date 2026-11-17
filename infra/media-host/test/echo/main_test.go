package main

import "testing"

func TestKeyLayoutLine(t *testing.T) {
	tests := []struct {
		value  string
		want   string
		wantOK bool
	}{
		{"true", `{"level":"INFO","msg":"playback_key_layout","layout":"round"}`, true},
		{"false", `{"level":"INFO","msg":"playback_key_layout","layout":"legacy"}`, true},
		{"", "", false},
		{"TRUE", "", false},
	}
	for _, tt := range tests {
		got, ok := keyLayoutLine(tt.value)
		if got != tt.want || ok != tt.wantOK {
			t.Errorf("keyLayoutLine(%q) = %q, %v; want %q, %v", tt.value, got, ok, tt.want, tt.wantOK)
		}
	}
}
