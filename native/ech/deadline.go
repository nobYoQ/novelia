package ech

import (
	"sync"
	"time"
)

// Phase deadlines are replaced at protocol boundaries, never inherited by a
// later phase. Zero means no deadline. A generation prevents a stopped callback
// from cancelling a new phase after racing with WroteRequest or a read finish.
type phaseDeadline struct {
	mu         sync.Mutex
	timer      *time.Timer
	generation uint64
	expired    bool
	finished   bool
	expire     func(string)
}

func newPhaseDeadline(expire func(string)) *phaseDeadline { return &phaseDeadline{expire: expire} }

func (d *phaseDeadline) start(timeout time.Duration, reason string) {
	d.mu.Lock()
	defer d.mu.Unlock()
	if d.expired || d.finished {
		return
	}
	d.generation++
	generation := d.generation
	if d.timer != nil {
		d.timer.Stop()
		d.timer = nil
	}
	if timeout <= 0 {
		return
	}
	d.timer = time.AfterFunc(timeout, func() {
		d.mu.Lock()
		if d.generation != generation || d.expired {
			d.mu.Unlock()
			return
		}
		d.expired = true
		d.mu.Unlock()
		d.expire(reason)
	})
}

func (d *phaseDeadline) stop() {
	d.mu.Lock()
	defer d.mu.Unlock()
	d.generation++
	d.finished = true
	if d.timer != nil {
		d.timer.Stop()
		d.timer = nil
	}
}
