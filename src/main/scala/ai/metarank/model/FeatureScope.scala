package ai.metarank.model

import ai.metarank.model.Event.{FeedbackEvent, InteractionEvent, ItemEvent, RankingEvent, UserEvent}
import ai.metarank.model.FeatureScope.ItemScope.scope
import ai.metarank.model.FeatureScope.TenantScope.tags
import io.circe.{Decoder, Encoder}
import io.findify.featury.model.Key
import io.findify.featury.model.Key.{FeatureName, Scope, Tag, Tenant}

import scala.util.Success

sealed trait FeatureScope {
  def scope: Scope
  def tags(event: Event): Iterable[Tag]
  def keys(event: Event, feature: FeatureName): Iterable[Key] =
    tags(event).map(tag => Key(tag, feature, Tenant(event.tenant)))
}

object FeatureScope {
  def tags(event: Event): Iterable[Tag] = {
    Iterable.concat(
      TenantScope.tags(event),
      ItemScope.tags(event),
      UserScope.tags(event),
      SessionScope.tags(event)
    )
  }
  case object TenantScope extends FeatureScope {
    val scope                                      = Scope("tenant")
    override def tags(event: Event): Iterable[Tag] = Some(Tag(scope, event.tenant))
  }

  case object ItemScope extends FeatureScope {
    val scope = Scope("item")

    override def tags(event: Event): Iterable[Tag] = event match {
      case e: RankingEvent     => e.items.toList.map(item => Tag(scope, item.id.value))
      case e: InteractionEvent => Some(Tag(scope, e.item.value))
      case e: ItemEvent        => Some(Tag(scope, e.item.value))
      case _                   => None
    }
  }
  case object UserScope extends FeatureScope {
    val scope = Scope("user")
    override def tags(event: Event): Iterable[Tag] = event match {
      case e: FeedbackEvent => e.user.map(user => Tag(scope, user.value))
      case e: UserEvent     => Some(Tag(scope, e.user.value))
      case _                => None
    }
  }
  case object SessionScope extends FeatureScope {
    val scope = Scope("session")
    override def tags(event: Event): Iterable[Tag] = event match {
      case e: FeedbackEvent => e.session.map(session => Tag(scope, session.value))
      case _                => None
    }
  }

  implicit val scopeEncoder: Encoder[FeatureScope] = Encoder.encodeString.contramap(_.scope.name)

  implicit val scopeDecoder: Decoder[FeatureScope] = Decoder.decodeString.emapTry {
    case TenantScope.scope.name  => Success(TenantScope)
    case ItemScope.scope.name    => Success(ItemScope)
    case UserScope.scope.name    => Success(UserScope)
    case SessionScope.scope.name => Success(SessionScope)
  }
}
