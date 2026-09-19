package RBT;

import core.Value;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class RBT<K extends Comparable<K>,V> {

    Node<K,V> root;
    private int size;

    public RBT(){
        this.root = null;
        this.size = 0;
    }

    public V get(K key){
        Node<K,V> node = searchNode(key);
        if(node != null && node.getKey().compareTo(key) == 0) return node.getVal();
        return null;
    }

    private Node<K,V> searchNode(K key){
        Node<K,V> parent = this.root;
        while(parent != null){
            int res = parent.getKey().compareTo(key);
            if(res < 0){
                if(parent.getRight() != null) parent = parent.getRight();
                else break;
            }else if(res > 0){
                if(parent.getLeft() != null) parent = parent.getLeft();
                else break;
            }else{
                break;
            }
        }
        return parent;
    }

    public int getSize(){
        return this.size;
    }

    public List<K> inOrderKeys(){
        List<Entry<K,V>> entries = new ArrayList<>();
        inOrderEntries(this.root, entries);

        return entries.stream()
                .map(Entry::getKey)
                .collect(Collectors.toList());
    }

    public void insert(K key, V value){
        this.root = insertNode(this.root, key, value);
        this.root.makeNodeBlack();
    }

    private Node<K,V> insertNode(Node<K,V> parent, K key, V value){
        if(parent == null){
            Node<K,V> newNode = new Node<>(key, value);
            this.size++;
            return newNode;
        }

        int res = parent.getKey().compareTo(key);
        if(res == 0){
            parent.setVal(value);
        }else if(res > 0){
            parent.setLeft(insertNode(parent.getLeft(), key, value));
        }else{
            parent.setRight(insertNode(parent.getRight(), key, value));
        }

        parent = fixUp(parent);
        return parent;
    }

    Node<K,V> fixUp(Node<K,V> node){
        if(node == null) return null;

        if(node.isRightChildRed() && node.isLeftChildBlack()){
            node = rotateLeft(node);
        }
        if(node.isLeftChildRed() && node.getLeft().isLeftChildRed()){
            node = rotateRight(node);
        }
        if(node.isLeftChildRed() && node.isRightChildRed()){
            flipColors(node);
        }

        return node;
    }

    public void flipColors(Node<K,V> node){
        if(node == null) return;
        node.flipColor();
        if(node.getLeft() != null) node.getLeft().flipColor();
        if(node.getRight() != null) node.getRight().flipColor();
    }

    Node<K,V> rotateRight(Node<K,V> node){
        Node<K,V> left = node.getLeft();
        if(left == null) return node;

        node.setLeft(left.getRight());
        left.setRight(node);

        swapColor(node, left);
        return left;
    }

    Node<K,V> rotateLeft(Node<K,V> node){
        Node<K,V> right = node.getRight();
        if(right == null) return node;

        node.setRight(right.getLeft());
        right.setLeft(node);

        swapColor(node, right);
        return right;
    }

    void swapColor(Node<K, V> node1, Node<K, V> node2){
        boolean isNode1Red = node1.isRed();
        if(node2.isRed()) node1.makeNodeRed();
        else node1.makeNodeBlack();

        if(isNode1Red) node2.makeNodeRed();
        else node2.makeNodeBlack();
    }

    public List<Entry<K, V>> rangeEntries(K low, K high){
        List<Entry<K, V>> list = new ArrayList<>();
        range(this.root, low, high, list);
        return list;
    }

    public List<K> range(K low, K high){
        List<Entry<K, V>> list = rangeEntries(low, high);
        range(this.root, low, high, list);
        return list.stream().map(kvEntry -> kvEntry.getKey()).toList();
    }

    void range(Node<K, V> node, K low, K high, List<Entry<K, V>> entries){
        if(node == null) return;
        K key = node.getKey();

        if(key.compareTo(low) > 0){
            range(node.getLeft(), low, high, entries);
        }
        if(low.compareTo(key) <= 0 && high.compareTo(key) >=0){
            entries.add(new Entry<>(node.getKey(), node.getVal()));
        }
        if(key.compareTo(high) < 0){
            range(node.getRight(), low, high, entries);
        }
    }

    public K floor(K key){
        Node<K,V> low = null;
        Node<K,V> curr = this.root;
        while(curr != null){
            int res = curr.getKey().compareTo(key);
            if(res == 0) return key;
            if(res < 0){
                low = curr;
                curr = curr.getRight();
            }else{
                curr = curr.getLeft();
            }
        }

        return low != null ? low.getKey() : null;
    }

    public K ceiling(K key){
        Node<K,V> high = null;
        Node<K,V> curr = this.root;
        while(curr != null){
            int res = curr.getKey().compareTo(key);
            if(res == 0) return key;
            if(res < 0){
                curr = curr.getRight();
            }else{
                high = curr;
                curr = curr.getLeft();
            }
        }

        return high != null ? high.getKey() : null;
    }

    Node<K,V> moveRedLeft(Node<K,V> node){
        flipColors(node);
        if(node.getRight() != null && node.getRight().isLeftChildRed()){
            node.setRight(rotateRight(node.getRight()));
            node = rotateLeft(node);
            flipColors(node);
        }
        return node;
    }

    Node<K,V> moveRedRight(Node<K,V> node){
        flipColors(node);
        if(node.getLeft() != null && node.getLeft().isLeftChildRed()){
            node = rotateRight(node);
            flipColors(node);
        }
        return node;
    }

    public void delete(K key){
        if(this.root == null || !searchNode(key).getKey().equals(key)) return;

        if(root.isLeftChildBlack() && root.isRightChildBlack()) root.makeNodeRed();
        root = delete(root, key);
        if(root != null) root.makeNodeBlack();
    }

    Node<K,V> delete(Node<K,V> node, K key){
        if(node.getKey().compareTo(key) > 0){
            if(node.isLeftChildBlack() && node.getLeft() != null && node.getLeft().isLeftChildBlack()) node = moveRedLeft(node);
            node.setLeft(delete(node.getLeft(), key));
        }else{
            if(node.isLeftChildRed()) node = rotateRight(node);
            if(node.getKey().equals(key) && node.getRight() == null){
                this.size--;
                return null;
            }
            if(node.isRightChildBlack() && node.getRight() != null && node.getRight().isLeftChildBlack()) node = moveRedRight(node);

            if(node.getKey().equals(key)){
                Node<K,V> x = min(node.getRight());
                node.setKey(x.getKey());
                node.setVal(x.getVal());
                node.setRight(deleteMin(node.getRight()));
            }else{
                node.setRight(delete(node.getRight(), key));
            }

        }

        return fixUp(node);
    }

    Node<K,V> min(Node<K,V> node){
        while(node.getLeft() != null) node = node.getLeft();
        return node;
    }

    Node<K,V> deleteMin(Node<K,V> node){
        if(node.getLeft() == null){
            this.size--;
            return null;
        }

        if(node.isLeftChildBlack() && node.getLeft() != null && node.getLeft().isLeftChildBlack()) node = moveRedLeft(node);
        node.setLeft(deleteMin(node.getLeft()));
        return fixUp(node);
    }

    public List<Entry<K,V>> entries(){
        List<Entry<K,V>> list = new ArrayList<>();
        inOrderEntries(root, list);
        return list;
    }

    void inOrderEntries(Node<K,V> node, List<Entry<K,V>> list){
        if(node == null) return;
        inOrderEntries(node.getLeft(), list);
        list.add(new Entry<K, V>(node.getKey(), node.getVal()));
        inOrderEntries(node.getRight(), list);
    }

}
