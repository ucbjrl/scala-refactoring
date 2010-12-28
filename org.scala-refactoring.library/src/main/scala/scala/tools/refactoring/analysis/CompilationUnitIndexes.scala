/*
 * Copyright 2005-2010 LAMP/EPFL
 */

package scala.tools.refactoring
package analysis

import collection.mutable.{HashMap, ListBuffer}

/**
 * A CompilationUnitIndex is a light-weight index that
 * holds all definitions and references in a compilation
 * unit. This index is built with the companion object, 
 * which traverses the whole compilation unit once and
 * then memoizes all relations.
 * 
 */
trait CompilationUnitIndexes {
  
  this: common.PimpedTrees with common.CompilerAccess =>
  
  import global._
  
  trait CompilationUnitIndex {
    def definitions: Map[Symbol, List[DefTree]]
    def references:  Map[Symbol, List[Tree]]
  }
  
  object CompilationUnitIndex {
  
    def apply(tree: Tree) = {
      
      val defs = new HashMap[Symbol, ListBuffer[DefTree]]
      val refs = new HashMap[Symbol, ListBuffer[Tree]]
      
      def processTree(tree: Tree): Unit = {
  
        def addDefinition(t: DefTree) {
          def add(s: Symbol) = 
            defs.getOrElseUpdate(s, new ListBuffer[DefTree]) += t
          
          add(t.symbol)
        }
  
        def addReference(s: Symbol, t: Tree) {
          def add(s: Symbol) = 
            refs.getOrElseUpdate(s, new ListBuffer[Tree]) += t

          add(s)
          
          s match {
            case _: ClassSymbol => ()
            /*
             * If we only have a TypeSymbol, we check if it is 
             * a reference to another symbol and add this to the
             * index as well.
             * 
             * This is needed for example to find the TypeTree
             * of a DefDef parameter-ValDef
             * */
            case ts: TypeSymbol =>
              ts.info match {
                case tr: TypeRef if tr.sym != null =>
                  add(tr.sym)
                case _ => ()
              }
            case _ => ()
          }
        }
  
        tree foreach {
          // The standard traverser does not traverse a TypeTree's original:
          case t: TypeTree if t.original != null =>
            processTree(t.original)
  
            (t.original, t.tpe) match {
              case (att @ AppliedTypeTree(_, args1), tref @ TypeRef(_, _, args2)) =>

                // add a reference for AppliedTypeTrees, e.g. List[T] adds a reference to List
                //addReference(tref.sym, t)

                // Special treatment for type ascription
                args1 zip args2 foreach {
                  case (i: RefTree, tpe: TypeRef) => 
                    addReference(tpe.sym, i)
                  case _ => ()
                }              
              case _ => ()
            }
            
          case t: DefTree if t.symbol != NoSymbol =>
            addDefinition(t)
          case t: RefTree =>
            val tree = if(t.pos.isRange) {
              t setPos fixTreePositionIncludingCarriageReturn(t.pos)
            } else t 
            addReference(t.symbol, tree)
          case t: TypeTree =>
            
            def handleType(typ: Type): Unit = typ match {
              case RefinedType(parents, _) =>
                parents foreach handleType
              case TypeRef(_, sym, _) =>
                addReference(sym, t)
              case _ => ()
            }
            
            handleType(t.tpe)
            
          case t @ Import(expr, _) if expr.tpe != null =>
            
            def handleImport(iss: List[ImportSelectorTree], sym: Symbol): Unit = iss match {
              case Nil => 
                ()
              case (t @ ImportSelectorTree(NameTree(name), _)) :: _ if (name.toString == sym.name.toString)=> 
                addReference(sym, t)
              case _ :: rest => 
                handleImport(rest, sym)
            }
            
            expr.tpe.members foreach (handleImport(t.Selectors(), _))
            
          case _ => ()
        }
      }
    
      processTree(tree)
      
      new CompilationUnitIndex {
        val definitions = defs.map {case (k, v) => k → v.toList} toMap
        val references = refs.map {case (k, v) => k → v.toList} toMap
      }
    }
  }
}