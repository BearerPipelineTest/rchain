package coop.rchain.casper.util

import cats.effect.{Blocker, ContextShift, Sync}
import cats.syntax.all._
import coop.rchain.casper.genesis.contracts.Vault
import coop.rchain.rholang.interpreter.util.RevAddress
import coop.rchain.shared.Log
import fs2.{io, text}

import java.nio.file.Path

object VaultParser {

  /**
    * Parser for wallets file used in genesis ceremony to set initial REV accounts.
    *
    * TODO: Create Blocker scheduler for file operations. For now it's ok because it's used only once at genesis.
    *   Cats Effect 3 removed ContextShift and Blocker.
    *    - https://typelevel.org/cats-effect/docs/migration-guide#blocker
    */
  def parse[F[_]: Sync: ContextShift: Log](vaultsPath: Path): F[Seq[Vault]] = {
    def readLines(blocker: Blocker) =
      io.file
        .readAll[F](vaultsPath, blocker, chunkSize = 4096)
        .through(text.utf8Decode)
        .through(text.lines)
        .filter(_.trim.nonEmpty)
        .evalMap { line =>
          val lineFormat = "<ETH_address>,<balance>,<private_key=0|contract=1>"

          // Line parser
          val ethAndBalance = tryWithMsg {
            val Array(fst, snd, _*) = line.split(",")
            (fst, snd)
          }(failMsg = s"INVALID LINE FORMAT: `$lineFormat`, actual: $line")

          // ETH address parser, converter to REV address
          def revAddress(ethAddressString: String) =
            RevAddress
              .fromEthAddress(ethAddressString)
              .liftTo[F](new Exception(s"INVALID ETH ADDRESS: `$ethAddressString`"))

          // Balance parser
          def revBalance(revBalanceStr: String) = tryWithMsg(revBalanceStr.toLong)(
            failMsg = s"INVALID WALLET BALANCE `$revBalanceStr`. Please put positive number."
          )

          // Parse REV address and balance
          ethAndBalance
            .flatMap(_.bitraverse(revAddress, revBalance))
            .map(Vault.tupled)
            .tupleRight(line)
        }
        .evalMap {
          case (vault, line) => Log[F].info(s"Wallet loaded: $line").as(vault)
        }
        .compile
        .to(Seq)
        .adaptErr {
          case ex: Throwable =>
            new Exception(s"FAILED PARSING WALLETS FILE: $vaultsPath\n$ex")
        }
    Blocker[F].use(readLines)
  }

  def parse[F[_]: Sync: ContextShift: Log](vaultsPathStr: String): F[Seq[Vault]] = {
    val vaultsPath = Path.of(vaultsPathStr)

    def readLines(blocker: Blocker) =
      io.file
        .exists(blocker, vaultsPath)
        .ifM(
          Log[F].info(s"Parsing wallets file $vaultsPath.") >> parse(vaultsPath),
          Log[F]
            .warn(s"WALLETS FILE NOT FOUND: $vaultsPath. No vaults will be put in genesis block.")
            .as(Seq.empty[Vault])
        )
    Blocker[F].use(readLines)
  }

  private def tryWithMsg[F[_]: Sync, A](f: => A)(failMsg: => String) =
    Sync[F].delay(f).adaptError { case _ => new Exception(failMsg) }
}
