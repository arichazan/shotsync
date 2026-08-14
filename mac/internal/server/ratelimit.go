package server

import (
	"sync"
	"time"
)

// bucket is a minimal token bucket used to rate-limit uploads.
type bucket struct {
	mu     sync.Mutex
	tokens float64
	cap    float64
	rate   float64 // tokens per second
	last   time.Time
}

func newBucket(perMinute float64, burst float64) *bucket {
	return &bucket{
		tokens: burst,
		cap:    burst,
		rate:   perMinute / 60.0,
		last:   time.Now(),
	}
}

// take consumes one token, returning false when rate-limited.
func (b *bucket) take() bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	now := time.Now()
	b.tokens += now.Sub(b.last).Seconds() * b.rate
	if b.tokens > b.cap {
		b.tokens = b.cap
	}
	b.last = now
	if b.tokens < 1 {
		return false
	}
	b.tokens--
	return true
}
