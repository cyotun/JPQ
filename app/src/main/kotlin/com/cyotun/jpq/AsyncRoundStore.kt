package com.cyotun.jpq

import android.content.Context
import android.util.Log
import com.cyotun.jpq.core.BoundedWriter
import com.cyotun.jpq.core.RoundLedger

class AsyncRoundStore(context: Context): AutoCloseable {
    private val lease: AutoCloseable=EvidenceWork.writer()
    private val repository=RoundRepository(context)
    // Repository retains 21 rounds; coalescing replaces only the same round's full checkpoint.
    private val writer=BoundedWriter<RoundRepository.Checkpoint>("jpq-round-save",21,
        key={ it.id },write={ repository.save(it) },
        failed={ Log.w("JPQTrace","round_checkpoint_unavailable",it) },
        finished={ try { repository.close() } finally { lease.close() } })
    fun save(ledger: RoundLedger) { RoundRepository.checkpoint(ledger)?.let { writer.offer(it) } }
    override fun close() { writer.close() }
}
